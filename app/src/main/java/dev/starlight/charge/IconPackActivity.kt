package dev.starlight.charge

import android.app.Activity
import android.os.Bundle

/** Entry point launchers use to discover Starlight's icon pack (res/xml/appfilter.xml). */
class IconPackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
