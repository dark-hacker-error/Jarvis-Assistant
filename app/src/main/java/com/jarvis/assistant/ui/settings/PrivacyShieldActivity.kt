package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.databinding.ActivityPrivacyShieldBinding
import com.jarvis.assistant.util.ThemeManager

class PrivacyShieldActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyShieldBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyShieldBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnJoinOfficialTelegram.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }

        binding.btnJoinTelegram.setOnClickListener {
            openUrl("https://t.me/+DmyO7NFUia01YTVl")
        }

        binding.btnJoinInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/techgptx.ai")
        }

        binding.btnJoinYoutube.setOnClickListener {
            openUrl("https://youtube.com/@TechGptX")
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
