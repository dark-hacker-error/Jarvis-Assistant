package com.jarvis.assistant.ui.permission

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import android.view.Gravity
import android.view.ViewGroup
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.databinding.ActivityPermissionBinding
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager

class PermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionBinding

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStates() }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updatePermissionStates() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionStates() }

    private val allPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updatePermissionStates() }
    private val contactsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updatePermissionStates() }
    private val callLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updatePermissionStates() }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updatePermissionStates() }
    private val bluetoothLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updatePermissionStates() }
    private val extraStatus = mutableMapOf<String, TextView>()
    private val extraButtons = mutableMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }
        addExtraPermissionCards()

        binding.btnGrantMic.setOnClickListener {
            if (!PermissionHelper.hasRecordAudioPermission(this)) {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else PermissionHelper.openAppSettings(this)
        }

        binding.btnGrantLocation.setOnClickListener {
            if (!PermissionHelper.hasLocationPermission(this)) {
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else PermissionHelper.openAppSettings(this)
        }

        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!PermissionHelper.hasNotificationPermission(this)) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else PermissionHelper.openAppSettings(this)
            } else {
                updatePermissionStates()
            }
        }

        binding.btnGrantOverlay.setOnClickListener {
            if (!PermissionHelper.hasOverlayPermission(this)) {
                PermissionHelper.requestOverlayPermission(this)
            } else PermissionHelper.requestOverlayPermission(this)
        }

        binding.btnGrantAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.btnGrantAll.setOnClickListener {
            requestAllMissingPermissions()
        }

        binding.btnOpenSettings.setOnClickListener {
            PermissionHelper.openAppSettings(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        val hasMic = PermissionHelper.hasRecordAudioPermission(this)
        val hasLoc = PermissionHelper.hasLocationPermission(this)
        val hasNotif = PermissionHelper.hasNotificationPermission(this)
        val hasOverlay = PermissionHelper.hasOverlayPermission(this)
        val hasA11y = PermissionHelper.isAccessibilityServiceEnabled(this)

        updateCardState(hasMic, binding.tvStatusMic, binding.btnGrantMic)
        updateCardState(hasLoc, binding.tvStatusLocation, binding.btnGrantLocation)
        updateCardState(hasNotif, binding.tvStatusNotification, binding.btnGrantNotification)
        updateCardState(hasOverlay, binding.tvStatusOverlay, binding.btnGrantOverlay)
        updateCardState(hasA11y, binding.tvStatusAccessibility, binding.btnGrantAccessibility)
        updateExtraPermission("contacts", PermissionHelper.hasContactsPermission(this))
        updateExtraPermission("phone", hasPermission(Manifest.permission.CALL_PHONE))
        updateExtraPermission("camera", hasPermission(Manifest.permission.CAMERA))
        updateExtraPermission("bluetooth", Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
        updateExtraPermission("battery", PermissionHelper.hasBatteryOptimizationPermission(this))
        updateExtraPermission("brightness", PermissionHelper.hasWriteSettingsPermission(this))

        if (hasMic && hasLoc && hasNotif && hasA11y) {
            binding.btnGrantAll.text = "ALL ESSENTIAL PERMISSIONS GRANTED ✓"
            binding.btnGrantAll.alpha = 0.7f
            binding.btnGrantAll.isEnabled = false
        } else {
            binding.btnGrantAll.text = "GRANT ALL PERMISSIONS"
            binding.btnGrantAll.alpha = 1.0f
            binding.btnGrantAll.isEnabled = true
        }
    }

    private fun updateCardState(granted: Boolean, statusTv: TextView, button: Button) {
        if (granted) {
            statusTv.text = "Active & Granted ✓"
            statusTv.setTextColor(Color.parseColor("#10B981")) // Emerald Green
            button.text = "Manage"
            button.alpha = 1.0f
            button.isEnabled = true
        } else {
            statusTv.text = "Action Required • Tap Grant"
            statusTv.setTextColor(Color.parseColor("#F59E0B")) // Amber
            button.text = "Grant"
            button.alpha = 1.0f
            button.isEnabled = true
        }
    }

    private fun requestAllMissingPermissions() {
        val missing = mutableListOf<String>()
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (!PermissionHelper.hasLocationPermission(this)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PermissionHelper.hasNotificationPermission(this)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.CAMERA).forEach {
            if (!hasPermission(it)) missing.add(it)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (missing.isNotEmpty()) {
            allPermissionsLauncher.launch(missing.toTypedArray())
        } else if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
            PermissionHelper.openAccessibilitySettings(this)
        } else if (!PermissionHelper.hasOverlayPermission(this)) {
            PermissionHelper.requestOverlayPermission(this)
        }
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun addExtraPermissionCards() {
        val scroll = (binding.root as ViewGroup).getChildAt(0) as? NestedScrollView ?: return
        val column = scroll.getChildAt(0) as? LinearLayout ?: return
        val insertion = (0 until column.childCount).firstOrNull { column.getChildAt(it).id == com.jarvis.assistant.R.id.cardAccessibilityPermission }
            ?.plus(1) ?: (column.childCount - 2).coerceAtLeast(0)
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun card(key: String, title: String, details: String, iconId: Int, action: () -> Unit) {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = androidx.core.content.ContextCompat.getDrawable(this@PermissionActivity, com.jarvis.assistant.R.drawable.bg_jarvis_card)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
            val icon = ImageView(this).apply {
                setImageResource(iconId)
                imageTintList = android.content.res.ColorStateList.valueOf(resolveThemeColor(com.jarvis.assistant.R.attr.themeAccent))
                background = androidx.core.content.ContextCompat.getDrawable(this@PermissionActivity, com.jarvis.assistant.R.drawable.bg_jarvis_avatar)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = null
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
            val heading = TextView(this).apply { text = title; setTextColor(resolveThemeColor(com.jarvis.assistant.R.attr.themeTextPrimary)); textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
            val status = TextView(this).apply { text = "Checking…"; setTextColor(resolveThemeColor(com.jarvis.assistant.R.attr.themeTextMuted)); textSize = 12f }
            labels.addView(heading); labels.addView(status)
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val button = Button(this).apply {
                text = "Grant"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = androidx.core.content.ContextCompat.getDrawable(this@PermissionActivity, com.jarvis.assistant.R.drawable.bg_jarvis_pill_button)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { action() }
            }
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
            val description = TextView(this).apply { text = details; setTextColor(resolveThemeColor(com.jarvis.assistant.R.attr.themeTextMuted)); textSize = 12f; setPadding(0, dp(10), 0, 0) }
            body.addView(row); body.addView(description)
            section.addView(body)
            extraStatus[key] = status
            extraButtons[key] = button
        }
        card("contacts", "Contacts", "Lets WhatsApp and calling features resolve saved contact names.", com.jarvis.assistant.R.drawable.ic_nav_chat) {
            if (!PermissionHelper.hasContactsPermission(this)) contactsLauncher.launch(Manifest.permission.READ_CONTACTS) else PermissionHelper.openAppSettings(this)
        }
        card("phone", "Phone calls", "Needed only when you ask JARVIS to place a normal phone call.", com.jarvis.assistant.R.drawable.ic_settings_voice) {
            if (!hasPermission(Manifest.permission.CALL_PHONE)) callLauncher.launch(Manifest.permission.CALL_PHONE) else PermissionHelper.openAppSettings(this)
        }
        card("camera", "Camera and flashlight", "Camera access is used only for the flashlight control.", com.jarvis.assistant.R.drawable.ic_settings_orb) {
            if (!hasPermission(Manifest.permission.CAMERA)) cameraLauncher.launch(Manifest.permission.CAMERA) else PermissionHelper.openAppSettings(this)
        }
        card("bluetooth", "Nearby Bluetooth devices", "Optional: allows JARVIS to detect and route audio to a connected headset.", com.jarvis.assistant.R.drawable.ic_settings_plug) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) else PermissionHelper.openAppSettings(this)
        }
        card("battery", "Background battery access", "Recommended on Vivo devices so voice service and overlay can stay active in the background.", com.jarvis.assistant.R.drawable.ic_settings_silent) {
            PermissionHelper.requestBatteryOptimization(this)
        }
        card("brightness", "Modify system settings", "Optional: lets JARVIS adjust screen brightness when you ask.", com.jarvis.assistant.R.drawable.ic_settings_palette) {
            PermissionHelper.requestWriteSettings(this)
        }
        val note = TextView(this).apply {
            text = "Screen sharing uses Android’s one-time screen-capture confirmation when you start sharing. Internet, network, notifications, and foreground-service access are managed by Android."
            setTextColor(resolveThemeColor(com.jarvis.assistant.R.attr.themeTextMuted)); textSize = 11f; setPadding(dp(4), dp(4), dp(4), dp(16))
        }
        section.addView(note)
        column.addView(section, insertion)
    }

    private fun updateExtraPermission(key: String, granted: Boolean) {
        val status = extraStatus[key] ?: return
        status.text = if (granted) "Granted ✓" else "Not granted"
        status.setTextColor(if (granted) Color.parseColor("#10B981") else Color.parseColor("#F59E0B"))
        extraButtons[key]?.text = if (granted) "Manage" else "Grant"
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
