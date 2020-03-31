package cz.covid19cz.erouska.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import cz.covid19cz.app.ui.base.UrlEvent
import cz.covid19cz.erouska.R
import cz.covid19cz.erouska.databinding.FragmentAboutBinding
import cz.covid19cz.erouska.ui.base.BaseFragment

class AboutFragment : BaseFragment<FragmentAboutBinding, AboutVM>(R.layout.fragment_about, AboutVM::class) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subscribe(UrlEvent::class) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.url)))
        }
    }
}