package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.SocialLinks
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var pendingStorageAction: (() -> Unit)? = null

    private val backupStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingStorageAction
        pendingStorageAction = null
        val granted = grants.values.all { it }
        if (granted && action != null) {
            action()
        } else if (!granted) {
            Toast.makeText(
                requireContext(),
                "Storage permission is needed for the Android 8/9 backup file",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        loadUserData()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    private fun loadUserData() {
        val userName = preferences.userName.ifBlank { "Roshan Hacker" }
        binding.tvProfileName.text = userName
        binding.tvProfileSubtitle.text = "JARVIS OPERATOR • PRIMARY USER"

        // Owner Voice Profile Status
        val hasVoiceProfile = preferences.hasOwnerVoiceProfile()
        if (hasVoiceProfile) {
            binding.tvOwnerVoiceBadge.text = "ACTIVE"
            binding.tvOwnerVoiceBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.tvOwnerVoiceSubtitle.text = "Voice Profile Active • Only you can speak"

            binding.tvProfileVoiceStatus.text = "VOICE ENROLLED"
            binding.tvProfileVoiceStatus.setTextColor(Color.parseColor("#10B981"))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_verified_badge)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        } else {
            binding.tvOwnerVoiceBadge.text = "SETUP"
            binding.tvOwnerVoiceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvOwnerVoiceSubtitle.text = "Not enrolled • Tap to record voice profile"

            binding.tvProfileVoiceStatus.text = "ENROLL VOICE"
            binding.tvProfileVoiceStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_settings_owner_voice)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
            )
        }

        // Language
        binding.tvCurrentLanguage.text = preferences.language

        // Persona & Voice
        val currentPersona = preferences.personality
        val currentVoice = preferences.voice
        binding.tvCurrentPersona.text = "$currentPersona • Voice: $currentVoice"
        binding.tvProfilePersonaChip.text = currentPersona.uppercase()

        // Silent Execution Mode
        binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode

        // Model
        val modelDisplay = preferences.aiModel.removePrefix("models/")
        binding.tvCurrentModel.text = "$modelDisplay • Live WebSocket"

        // Theme
        binding.tvCurrentTheme.text = when (preferences.appTheme) {
            "dark" -> "Dark Theme"
            "red" -> "Red Theme"
            "sunset" -> "Sunset Theme"
            "custom" -> "Custom (${preferences.customThemeColor})"
            else -> "Light Theme"
        }

        binding.tvBottomNavStyle.text = when (preferences.bottomNavStyle) {
            "classic" -> "Classic"
            else -> "Glass (iPhone)"
        }

        // MCP + Shizuku status. These are additive extensions; when Shizuku is
        // missing the rest of the settings screen remains fully usable.
        val app = JarvisApp.instance
        val enabledServers = app.mcpManager.enabledServerCount()
        val enabledTools = app.mcpManager.enabledToolCount()
        binding.tvMcpSummary.text = if (enabledServers == 0) {
            "No MCP enabled • Tap to add tools"
        } else {
            "$enabledServers MCP(s) enabled • $enabledTools tools available"
        }
        binding.tvShizukuSummary.text = app.shizukuManager.statusText()
        binding.tvBackupSummary.text = app.backupCoordinator.statusText()
        lifecycleScope.launch {
            app.backupCoordinator.refreshStatus()
            if (_binding != null) {
                binding.tvBackupSummary.text = app.backupCoordinator.statusText()
            }
        }
    }

    private fun setupClickListeners() {
        // Profile Card -> Change Name
        binding.cardProfile.setOnClickListener {
            showEditNameDialog()
        }

        // Voice Status Chip on Profile Card -> Opens Voice Enrollment
        binding.chipProfileVoiceStatus.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        // Section 1: AI & Voice Intelligence
        binding.rowOwnerVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.rowPersonaVoice.setOnClickListener {
            startActivity(Intent(requireContext(), PersonaActivity::class.java))
        }

        binding.switchSilentMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSilentExecutionMode = isChecked
            val msg = if (isChecked) "Silent Execution Mode Enabled" else "Silent Execution Mode Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // Section 2: System & Intelligence Data
        binding.rowApiConnection.setOnClickListener {
            startActivity(Intent(requireContext(), ApiConnectionActivity::class.java))
        }

        binding.rowMemory.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.rowPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        binding.rowOverlayAvatars.setOnClickListener {
            startActivity(Intent(requireContext(), OverlayAvatarActivity::class.java))
        }

        binding.rowApplications.setOnClickListener {
            startActivity(Intent(requireContext(), HandsFreeActivity::class.java))
        }

        binding.rowMcpTools.setOnClickListener {
            startActivity(Intent(requireContext(), McpSettingsActivity::class.java))
        }

        binding.rowShizukuAccess.setOnClickListener {
            startActivity(Intent(requireContext(), ShizukuSettingsActivity::class.java))
        }

        binding.rowBackupRestore.setOnClickListener {
            showBackupDialog()
        }

        // Section 3: Appearance & Security
        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(childFragmentManager, ThemeSelectionBottomSheet.TAG)
        }

        binding.rowBottomNavStyle.setOnClickListener {
            showBottomNavStyleDialog()
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyShieldActivity::class.java))
        }

        binding.rowAppIdentity.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("JARVIS App Identity")
                .setMessage(com.jarvis.assistant.security.AppIdentityVerifier.report(requireContext()))
                .setPositiveButton("Close", null)
                .show()
        }

        // Social Media Community Buttons
        binding.btnSocialTelegram.setOnClickListener {
            openUrl(SocialLinks.TELEGRAM)
        }

        binding.btnSocialInstagram.setOnClickListener {
            openUrl(SocialLinks.INSTAGRAM)
        }

        binding.btnSocialWebsite.setOnClickListener {
            openUrl(SocialLinks.WEBSITE)
        }

        binding.tvFooterCredit.setOnClickListener {
            openUrl(SocialLinks.WEBSITE)
        }
    }

    private fun showBackupDialog() {
        val app = JarvisApp.instance
        val status = app.backupCoordinator.statusText()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Backup & Restore")
            .setMessage(
                "$status\n\n" +
                    "The snapshot is encrypted and stored in Downloads/Jarvis. It survives an " +
                    "app uninstall and is restored automatically after reinstalling with the " +
                    "same signing key.\n\n" +
                    "Included: memories, chat history, personal preferences, Gemini/YouTube " +
                    "API keys and custom MCP servers. Local values always win, so restoring " +
                    "never overwrites something you changed later."
            )
            .setItems(arrayOf("Backup now", "Restore from backup", "Delete backup")) { _, which ->
                when (which) {
                    0 -> withBackupStorage { performBackup() }
                    1 -> withBackupStorage { performRestore() }
                    2 -> confirmDeleteBackup()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun withBackupStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            action()
            return
        }
        val readGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (readGranted && writeGranted) {
            action()
            return
        }
        pendingStorageAction = action
        backupStorageLauncher.launch(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        )
    }

    private fun performBackup() {
        val app = JarvisApp.instance
        lifecycleScope.launch {
            val result = app.backupCoordinator.backupNow("manual", force = true)
            app.backupCoordinator.refreshStatus()
            updateBackupSummary()
            val message = result.fold(
                onSuccess = { "Encrypted backup saved to ${app.backupCoordinator.statusText()}" },
                onFailure = { "Backup failed: ${it.message ?: it.javaClass.simpleName}" }
            )
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun performRestore() {
        val app = JarvisApp.instance
        lifecycleScope.launch {
            val report = app.backupCoordinator.restoreNow()
            app.backupCoordinator.refreshStatus()
            updateBackupSummary()
            val message = when {
                report.error.isNotBlank() -> "Restore failed: ${report.error}"
                report.restored -> buildString {
                    append("Restore complete.")
                    if (report.memories > 0) append("\n• ${report.memories} memories")
                    if (report.chatTurns > 0) append("\n• ${report.chatTurns} chat turns")
                    if (report.geminiKey) append("\n• Gemini API key")
                    if (report.youtubeKey) append("\n• YouTube API key")
                    if (report.customServers > 0) append("\n• ${report.customServers} custom MCP server(s)")
                    if (report.preferencesRestored) append("\n• Personal preferences")
                }
                else -> "Nothing new to restore. Local data already contains everything from the backup."
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (report.error.isNotBlank()) "Restore failed" else "Backup restore")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun confirmDeleteBackup() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete persistent backup?")
            .setMessage(
                "This permanently removes the encrypted snapshot from Downloads/Jarvis. " +
                    "Memories and API keys already on this device stay untouched."
            )
            .setPositiveButton("Delete") { _, _ ->
                val app = JarvisApp.instance
                lifecycleScope.launch {
                    val deleted = app.backupCoordinator.deleteBackup()
                    app.backupCoordinator.refreshStatus()
                    updateBackupSummary()
                    Toast.makeText(
                        requireContext(),
                        if (deleted) "Persistent backup deleted" else "Could not delete the backup",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateBackupSummary() {
        if (_binding != null) {
            binding.tvBackupSummary.text = JarvisApp.instance.backupCoordinator.statusText()
        }
    }

    private fun showBottomNavStyleDialog() {
        val options = arrayOf("Glass (iPhone)", "Classic")
        val selected = if (preferences.bottomNavStyle == "classic") 1 else 0
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Bottom Navigation Style")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                preferences.bottomNavStyle = if (which == 1) "classic" else "glass"
                binding.tvBottomNavStyle.text = options[which]
                (activity as? MainActivity)?.applyBottomNavStyle()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLanguageDialog() {
        val languages = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentIndex = languages.indexOfFirst { it.equals(preferences.language, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvCurrentLanguage.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWakeWordDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Wake Words & Sensitivity")
            .setMessage("• Wake Words: 'Jarvis', 'Hey Jarvis'\n• Acoustic Trigger: Double Clap detection\n• Standby Mode: Low-power local Vosk offline engine\n\nWhen standby listening is active, Jarvis awakens instantly upon hearing your voice.")
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showAppAutomationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Autonomous App Automation")
            .setMessage("Jarvis is integrated with your device capabilities:\n\n• WhatsApp: Hands-free messaging & calls\n• YouTube: Search and playback automation\n• Phone Controls: Flashlight, Volume, Brightness, Bluetooth\n• Apps & Tools: Launch, search, and manage apps via voice")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showEditNameDialog() {
        val input = EditText(requireContext()).apply {
            setText(preferences.userName.ifBlank { "Roshan Hacker" })
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Operator Name")
            .setMessage("Enter the name Jarvis should use to address you:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    preferences.userName = newName
                    loadUserData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
