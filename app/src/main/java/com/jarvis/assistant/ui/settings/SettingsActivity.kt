package com.jarvis.assistant.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.settingsContainer)?.let {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(it)
        }
    }
}
