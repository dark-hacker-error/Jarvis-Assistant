package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.ActivityHandsFreeBinding
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.SocialLinks
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.voice.KernelSuRuntimeHelper
import com.jarvis.assistant.wake.WakeContract
import kotlinx.coroutines.launch

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
        updateNativeWakeStatus()
        updateWakeServiceStatus()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnToggleAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnActivateNativeWake.setOnClickListener {
            activateNativeWake()
        }

        binding.btnToggleWakeService.setOnClickListener {
            toggleWakeService()
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
            openUrl(SocialLinks.TELEGRAM)
        }

        binding.btnHandsFreeDiscussion.setOnClickListener {
            openUrl(SocialLinks.TELEGRAM)
        }

        binding.btnHandsFreeInstagram.setOnClickListener {
            openUrl(SocialLinks.INSTAGRAM)
        }

        binding.tvFooterHandsFree.setOnClickListener {
            openUrl(SocialLinks.WEBSITE)
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

    private fun updateNativeWakeStatus() {
        val active = KernelSuRuntimeHelper.isVoiceInteractionActive(this)
        val configured = KernelSuRuntimeHelper.isVoiceInteractionConfigured(this)
        val overlayAllowed = Settings.canDrawOverlays(this)

        val status = when {
            active && overlayAllowed -> "NATIVE WAKE: ACTIVE • ZERO NOTIFICATION"
            active -> "NATIVE WAKE: ACTIVE • ALLOW OVERLAY FOR THE ORB"
            configured -> "CONFIGURED • WAITING FOR SYSTEM BIND"
            else -> "NATIVE WAKE: NOT ACTIVE • TAP TO ENABLE"
        }
        binding.tvNativeWakeStatus.text = status
        binding.tvNativeWakeStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    active && overlayAllowed -> R.color.status_green
                    configured || active -> R.color.status_amber
                    else -> R.color.status_red
                }
            )
        )
    }

    private fun updateWakeServiceStatus() {
        val prefs = (application as JarvisApp).preferences
        val enabled = prefs.isWakeServiceEnabled
        val running = WakeContract.isServiceRunning(this)
        val micAllowed = PermissionHelper.hasRecordAudioPermission(this)
        val needsEnrollment = prefs.isOwnerVoiceEnabled && !prefs.hasAnyOwnerVoiceProfile()
        val overlayAllowed = Settings.canDrawOverlays(this)

        val status = when {
            running -> "PART 1 ACTIVE • SAY \"JARVIS\""
            enabled && !micAllowed -> "MICROPHONE PERMISSION NEEDED"
            enabled && needsEnrollment -> "ENABLED • ENROLL OWNER VOICE"
            enabled && !overlayAllowed -> "ENABLED • ALLOW OVERLAY FOR THE ORB"
            enabled -> "ENABLED • STARTING..."
            else -> "PART 1 OFF • LEGACY STANDBY ACTIVE"
        }
        binding.tvWakeServiceStatus.text = status
        binding.tvWakeServiceStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    running -> R.color.status_green
                    enabled -> R.color.status_amber
                    else -> R.color.status_red
                }
            )
        )
        binding.btnToggleWakeService.text =
            if (enabled) "Disable Ultra-Light Wake" else "Enable Ultra-Light Wake"
    }

    private fun toggleWakeService() {
        val prefs = (application as JarvisApp).preferences
        if (prefs.isWakeServiceEnabled) {
            prefs.isWakeServiceEnabled = false
            WakeContract.stop(this)
            Toast.makeText(
                this,
                "Ultra-light wake disabled. The legacy standby listener will be used.",
                Toast.LENGTH_LONG
            ).show()
            updateWakeServiceStatus()
            return
        }

        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            Toast.makeText(
                this,
                "Allow Microphone first to use the wake word.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, PermissionActivity::class.java))
            return
        }

        prefs.isWakeServiceEnabled = true
        val needsEnrollment = prefs.isOwnerVoiceEnabled && !prefs.hasAnyOwnerVoiceProfile()
        val started = WakeContract.start(this)
        Toast.makeText(
            this,
            when {
                !started -> "Could not start wake service. Check permissions."
                needsEnrollment ->
                    "Wake enabled. Say \"Jarvis\". Enroll your voice to lock it to you."
                else -> "Ultra-light wake enabled. Say \"Jarvis\"."
            },
            Toast.LENGTH_LONG
        ).show()
        updateWakeServiceStatus()
    }

    private fun activateNativeWake() {
        binding.btnActivateNativeWake.isEnabled = false
        binding.tvNativeWakeStatus.text = "REQUESTING KERNELSU ROOT..."
        lifecycleScope.launch {
            val result = KernelSuRuntimeHelper.activate(this@HandsFreeActivity)
            binding.btnActivateNativeWake.isEnabled = true
            if (result.success) {
                updateNativeWakeStatus()
                Toast.makeText(
                    this@HandsFreeActivity,
                    "Silent wake configured. The system will bind it after refreshing the assistant.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                binding.tvNativeWakeStatus.text = "ROOT SETUP FAILED • OPEN ASSISTANT SETTINGS"
                Toast.makeText(
                    this@HandsFreeActivity,
                    result.error ?: "KernelSU command failed",
                    Toast.LENGTH_LONG
                ).show()
                openVoiceInputSettings()
            }
        }
    }

    private fun openVoiceInputSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, "Unable to open assistant settings", Toast.LENGTH_SHORT).show()
            }
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
