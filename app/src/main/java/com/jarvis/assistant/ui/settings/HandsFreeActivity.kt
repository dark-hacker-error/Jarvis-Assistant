package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityHandsFreeBinding
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager

class HandsFreeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHandsFreeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHandsFreeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnToggleAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnOpenAllPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        binding.btnConfigureApi.setOnClickListener {
            startActivity(Intent(this, ApiConnectionActivity::class.java))
        }

        binding.btnHandsFreeOfficialTelegram.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }

        binding.btnHandsFreeDiscussion.setOnClickListener {
            openUrl("https://t.me/+DmyO7NFUia01YTVl")
        }

        binding.btnHandsFreeInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/techgptx.ai")
        }

        binding.tvFooterHandsFree.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }
    }

    private fun updateServiceStatus() {
        val isEnabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        if (isEnabled) {
            binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.tvAccessibilityStatus.text = "HANDS-FREE SERVICE: ACTIVE"
            binding.tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.btnToggleAccessibility.text = "Accessibility Service is Active"
        } else {
            binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_red)
            )
            binding.tvAccessibilityStatus.text = "SERVICE DISABLED • TAP TO ENABLE"
            binding.tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_red)
            )
            binding.btnToggleAccessibility.text = "Enable Accessibility Service"
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }
}
