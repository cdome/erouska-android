package cz.covid19cz.erouska.bt

import android.app.PendingIntent
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.databinding.ObservableArrayList
import cz.covid19cz.erouska.AppConfig
import cz.covid19cz.erouska.bt.entity.ScanSession
import cz.covid19cz.erouska.db.DatabaseRepository
import cz.covid19cz.erouska.db.ScanDataEntity
import cz.covid19cz.erouska.db.SharedPrefsRepository
import cz.covid19cz.erouska.ext.asHexLower
import cz.covid19cz.erouska.ext.execute
import cz.covid19cz.erouska.ext.hexAsByteArray
import cz.covid19cz.erouska.ext.hoursToMilis
import cz.covid19cz.erouska.receiver.BtScanReceiver
import cz.covid19cz.erouska.utils.L
import cz.covid19cz.erouska.utils.isBluetoothEnabled
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import no.nordicsemi.android.support.v18.scanner.*
import no.nordicsemi.android.support.v18.scanner.ScanSettings.MATCH_MODE_STICKY
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap


class BluetoothRepository(
    val context: Context,
    private val db: DatabaseRepository,
    private val btManager: BluetoothManager,
    private val prefs : SharedPrefsRepository
) {

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("1440dd68-67e4-11ea-bc55-0242ac130003")
        val GATT_CHARACTERISTIC_UUID: UUID = UUID.fromString("9472fbde-04ff-4fff-be1c-b9d3287e8f28")
        const val APPLE_MANUFACTURER_ID = 76
    }

    private var iosPendingIntent: PendingIntent? = null
    private var androidPendingIntent: PendingIntent? = null
    private val scanResultsMap = HashMap<String, ScanSession>()
    private val discoveredIosDevices = HashMap<String, ScanSession>()
    private val gattQueue: Queue<GattConnectionQueueEntry> = LinkedList()
    val scanResultsList = ObservableArrayList<ScanSession>()

    private var isAdvertising = false
    private var isScanning = false
    private var isScanningIosOnBackground = false

    private var gattFailDisposable: Disposable? = null

    val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onScanResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            BluetoothLeScannerCompat.getScanner().stopScan(this)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach {
                onScanResult(it)
            }
        }
    }

    val scanIosOnBackgroundCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onScanIosOnBackgroundResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanningIosOnBackground = false
            BluetoothLeScannerCompat.getScanner().stopScan(this)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach {
                onScanIosOnBackgroundResult(it)
            }
        }
    }

    private val advertisingCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            L.d("BLE advertising started.")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            L.e("BLE advertising failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = gatt.device.address

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                L.d("GATT connected. Mac: ${gatt.device.address}")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (discoveredIosDevices[mac]?.deviceId == ScanSession.DEFAULT_BUID) {
                    gattFailDisposable = Observable.timer(5, TimeUnit.SECONDS).subscribe {
                        // Unlock mac address slot for retry after 5 seconds (prevent DDOSing GATT server)
                        L.d("Unlocking GATT slot. Mac: ${gatt.device.address}")
                        discoveredIosDevices.remove(gatt.device.address)
                        gattFailDisposable?.dispose()
                    }
                }
                disconnectFromGatt(gatt)
                L.d("GATT disconnected. Mac: ${gatt.device.address}")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (GATT_CHARACTERISTIC_UUID == characteristic?.uuid) {
                val buid = characteristic.value?.asHexLower
                val mac = gatt.device?.address

                if (buid != null) {
                    L.d("GATT BUID found. Mac ${gatt.device.address}. BUID: $buid")
                    discoveredIosDevices[mac]?.let { s ->
                        Observable.just(s).map { session ->
                            session.deviceId = buid
                            scanResultsMap[buid] = session
                            session
                        }.execute({
                            scanResultsList.add(it)
                        }, {
                            L.e(it)
                        })
                    }
                } else {
                    L.e("GATT BUID not found on $mac")
                }
                disconnectFromGatt(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectFromGatt(gatt)
                return
            }
            val characteristic =
                gatt.getService(SERVICE_UUID)?.getCharacteristic(GATT_CHARACTERISTIC_UUID)
            if (characteristic != null) {
                gatt.readCharacteristic(characteristic)
            } else {
                disconnectFromGatt(gatt)
            }
        }
    }



    fun hasBle(c: Context): Boolean {
        return c.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    fun isBtEnabled(): Boolean {
        return btManager.isBluetoothEnabled()
    }

    fun startScanning() {
        if (isScanning) {
            stopScanning()
        }

        if (!isBtEnabled()) {
            L.d("Bluetooth disabled, can't start scanning")
            return
        }

        L.d("Starting BLE scanning in mode: ${AppConfig.scanMode}")

        val androidScannerSettings: ScanSettings = ScanSettings.Builder()
            .setLegacy(true)
            .setScanMode(AppConfig.scanMode)
            .setUseHardwareFilteringIfSupported(true)
            .setUseHardwareBatchingIfSupported(true)
            .setMatchMode(MATCH_MODE_STICKY)
            .setReportDelay(10000)
            .build()

        val iOSScannerSettings: ScanSettings = ScanSettings.Builder()
            .setLegacy(false)
            .setScanMode(AppConfig.scanMode)
            .setUseHardwareFilteringIfSupported(true)
            .setUseHardwareBatchingIfSupported(true)
            .setReportDelay(10000)
            .setMatchMode(MATCH_MODE_STICKY)
            .build()

        val androidIntent = Intent(context, BtScanReceiver::class.java)
        androidIntent.action = BtScanReceiver.ACTION_ANDROID
        androidPendingIntent =
            PendingIntent.getBroadcast(context, 1, androidIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val iosIntent = Intent(context, BtScanReceiver::class.java)
        iosIntent.action = BtScanReceiver.ACTION_IOS
        iosPendingIntent =
            PendingIntent.getBroadcast(context, 2, iosIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        BluetoothLeScannerCompat.getScanner().startScan(
            listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            ),
            androidScannerSettings,
            context,
            androidPendingIntent!!
        )
        isScanningIosOnBackground = true

        BluetoothLeScannerCompat.getScanner().startScan(
            listOf(
                ScanFilter.Builder()
                    .setManufacturerData(APPLE_MANUFACTURER_ID, byteArrayOf(), byteArrayOf())
                    .build()
            ),
            iOSScannerSettings,
            context,
            iosPendingIntent!!
        )
        isScanning = true
    }

    fun stopScanning() {
        isScanning = false
        isScanningIosOnBackground = false
        L.d("Stopping BLE scanning")
        iosPendingIntent?.let { BluetoothLeScannerCompat.getScanner().stopScan(context, it) }
        androidPendingIntent?.let { BluetoothLeScannerCompat.getScanner().stopScan(context, it) }
        saveDataAndClearScanResults()
        gattFailDisposable?.dispose()
    }

    private fun saveDataAndClearScanResults() {
        L.d("Saving data to database")
        Observable.just(scanResultsMap.values.toTypedArray())
            .map { tempArray ->
                for (item in tempArray) {
                    item.calculate()
                    val scanResult = ScanDataEntity(
                        0,
                        item.deviceId,
                        item.timestampStart,
                        item.timestampEnd,
                        item.avgRssi,
                        item.medRssi,
                        item.rssiCount
                    )
                    L.d("Saving: $scanResult")

                    db.add(scanResult)
                }
                dbCleanup()
                tempArray.size
            }.execute({
                L.d("$it records saved")
                clearScanResults()
            }, { L.e(it) })
    }

    private fun onScanResult(result: ScanResult) {
        result.scanRecord?.bytes?.let { bytes ->
            if (isServiceUUIDMatch(result) || canBeIosOnBackground(result.scanRecord)) {
                val deviceId = getBuidFromAdvertising(result.scanRecord!!)
                if (deviceId != null) {
                    // It's time to handle Android Device
                    handleAndroidDevice(result, deviceId)
                } else {
                    // It's time to handle iOS Device
                    handleIosDevice(result)
                }
            }
        }
    }

    private fun onScanIosOnBackgroundResult(result: ScanResult) {
        result.scanRecord?.let {
            if (!isServiceUUIDMatch(result) && canBeIosOnBackground(it)) {
                // It's time to handle iOS Device in background
                handleIosDevice(result)
            }
        }
    }

    private fun isServiceUUIDMatch(result: ScanResult): Boolean {
        return result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
    }

    /**
     * iOS doesn't send UUID with screen off and app in background so we have to find another way
     * how to detect if there is an iOS device on the other side.
     *
     * iOS devices send manufacturer specific data that look something like this:
     * 0x4C000100000000000000000200000000000000
     *
     * This checks for the apple inc. key and then matches the data against the above
     */
    private fun canBeIosOnBackground(scanRecord: ScanRecord?): Boolean {
        return scanRecord?.manufacturerSpecificData?.get(APPLE_MANUFACTURER_ID)?.let { data ->
            data.size > 10 && data[0] == 0x01.toByte() && data[1] == 0x00.toByte() && data[9] == 0x02.toByte()
        } ?: false
    }

    private fun handleAndroidDevice(result: ScanResult, deviceId: String) {
        if (!scanResultsMap.containsKey(deviceId)) {
            val newEntity = ScanSession(deviceId, result.device.address)
            newEntity.addRssi(result.rssi)
            scanResultsList.add(newEntity)
            scanResultsMap[deviceId] = newEntity
            L.d("Found new Android device: $deviceId")
        }

        scanResultsMap[deviceId]?.let { entity ->
            entity.addRssi(result.rssi)
            L.d("Device (Android) $deviceId - RSSI ${result.rssi}")
        }
    }

    private fun handleIosDevice(result: ScanResult) {
        if (!discoveredIosDevices.containsKey(result.device.address)) {
            L.d("Found new iOS: Mac: ${result.device.address}")
            getBuidFromGatt(result)
        } else {
            discoveredIosDevices[result.device.address]?.let {
                if (it.deviceId != ScanSession.DEFAULT_BUID && !scanResultsMap.containsKey(it.deviceId)) {
                    scanResultsMap[it.deviceId] = it
                    scanResultsList.add(it)
                }
                it.addRssi(result.rssi)
                L.d("Device (iOS) ${it.deviceId} - RSSI ${result.rssi}")
            }
            return
        }
    }

    private fun getBuidFromGatt(result: ScanResult) {
        val mac = result.device.address
        val session = ScanSession(mac = mac)
        session.addRssi(result.rssi)
        discoveredIosDevices[mac] = session
        L.d("Enqueued for GATT discovery. Mac:$mac")
        gattQueue.offer(GattConnectionQueueEntry(result.device.address))
        connectToGatt()
    }

    private fun connectToGatt() {
        gattQueue.peek()?.let { connectionQueueEntry ->
            if (!connectionQueueEntry.isRunning) {
                L.d("Connecting to GATT . Mac:${connectionQueueEntry.macAddress}")
                val device = btManager.adapter?.getRemoteDevice(connectionQueueEntry.macAddress)
                device?.connectGatt(context, false, gattCallback)
                connectionQueueEntry.isRunning = true
            } else {
                L.d("Waiting for previous GATT operation to finish. Currently running: Mac:${connectionQueueEntry.macAddress}")
            }
        }
    }

    private fun disconnectFromGatt(gatt: BluetoothGatt) {
        gatt.disconnect()
        gatt.close()
        gattQueue.poll()?.let {
            L.d("Removing finished GATT connection. Mac:${it.macAddress}")
        }
        connectToGatt()
    }

    private fun getBuidFromAdvertising(scanRecord: ScanRecord): String? {
        scanRecord.serviceData?.apply {
            return get(ParcelUuid(SERVICE_UUID))?.asHexLower ?: get(keys.first())?.asHexLower
        }
        return null
    }

    fun clearScanResults() {
        scanResultsList.clear()
        scanResultsMap.clear()
        clearIosDevices()
    }

    private fun clearIosDevices() {
        // Don't clear whole iOS device cache to preventing DDOS GATT
        for (device in discoveredIosDevices) {
            device.value.reset()
        }
    }

    fun supportsAdvertising(): Boolean {
        return btManager.adapter?.isMultipleAdvertisementSupported ?: false
    }

    fun startAdvertising(buid: String) {
        val power = AppConfig.advertiseTxPower

        if (isAdvertising) {
            stopAdvertising()
        }

        if (!isBtEnabled()) {
            L.d("Bluetooth disabled, can't start advertising")
            return
        }

        L.d("Starting BLE advertising with power $power")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AppConfig.advertiseMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(power)
            .build()

        val parcelUuid = ParcelUuid(SERVICE_UUID)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(parcelUuid)
            .build()

        val scanData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(parcelUuid, buid.hexAsByteArray).build()

        btManager.adapter?.bluetoothLeAdvertiser?.startAdvertising(
            settings,
            data,
            scanData,
            advertisingCallback
        )
    }

    fun stopAdvertising() {
        L.d("Stopping BLE advertising")
        isAdvertising = false
        btManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertisingCallback)
    }

    private fun dbCleanup(){
        if (System.currentTimeMillis() - prefs.getLastDbCleanupTimestamp() > 24.hoursToMilis()) {
            L.d("Deleting data older than ${AppConfig.persistDataDays} days")
            val rows = db.deleteOldData()
            L.d("$rows records deleted")
            prefs.saveLastDbCleanupTimestamp(System.currentTimeMillis())
        }
    }

    data class GattConnectionQueueEntry(val macAddress: String, var isRunning: Boolean = false)
}