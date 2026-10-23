package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityApiConnectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val testHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadCurrentPreferences()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadCurrentPreferences() {
        binding.etApiKey.setText(preferences.apiKey)
        binding.etCustomEndpoint.setText(preferences.customEndpoint)

        if (!preferences.canStoreApiKeySecurely) {
            binding.tvLiveStatus.text = "SECURE STORAGE UNAVAILABLE • API KEY DISABLED"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        }

        when (preferences.aiModel) {
            GeminiConstants.MODEL_3_8_LIVE -> binding.rbModelFlash20.isChecked = true
            GeminiConstants.MODEL_3_1_FLASH_AGENT -> binding.rbModelFlash15.isChecked = true
            else -> binding.rbModelFlashThinking.isChecked = true
        }

        if (!preferences.canStoreApiKeySecurely) {
            // Keep the secure-storage warning set above.
        } else if (preferences.apiKey.isNotBlank()) {
            binding.tvLiveStatus.text = "KEY CONFIGURED • READY"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            binding.tvLiveStatus.text = "NO API KEY • ENTER KEY BELOW"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EAB308"))
        }

        if (preferences.customEndpoint.isNotBlank()) {
            binding.tvEndpointSummary.text = "Active Endpoint: ${preferences.customEndpoint}"
        }
    }

    private fun setupListeners() {
        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasted = item?.text?.toString()?.trim() ?: ""
            if (pasted.isNotBlank()) {
                binding.etApiKey.setText(pasted)
                Toast.makeText(this, "API Key pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearKey.setOnClickListener {
            binding.etApiKey.setText("")
        }

        binding.btnTestConnection.setOnClickListener {
            val enteredKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (enteredKey.isBlank()) {
                Snackbar.make(binding.root, "Please enter an API key first", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testConnection(enteredKey)
        }

        binding.btnSaveConnection.setOnClickListener {
            saveAndApply()
        }
    }

    private fun testConnection(apiKey: String) {
        binding.btnTestConnection.isEnabled = false
        binding.tvPingResult.text = "Testing ping..."
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                    val request = Request.Builder().url(url).build()
                    testHttpClient.newCall(request).execute().use { response ->
                        val duration = System.currentTimeMillis() - startTime
                        if (response.isSuccessful) {
                            Pair(true, duration)
                        } else {
                            Pair(false, response.code)
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, -1)
                }
            }

            binding.btnTestConnection.isEnabled = true
            if (result.first) {
                val ping = result.second
                binding.tvPingResult.text = "Ping: ${ping}ms • Valid"
                binding.tvLiveStatus.text = "CONNECTED • HANDSHAKE OK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "Connection verified! Ping: ${ping}ms", Snackbar.LENGTH_LONG).show()
            } else {
                val code = result.second
                binding.tvPingResult.text = if (code == -1) "Network Timeout" else "HTTP $code Error"
                binding.tvLiveStatus.text = "FAILED • INVALID KEY OR NETWORK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                Snackbar.make(binding.root, "Connection failed. Please verify API key.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndApply() {
        val key = binding.etApiKey.text?.toString()?.trim() ?: ""
        val customEp = binding.etCustomEndpoint.text?.toString()?.trim() ?: ""

        val selectedModel = when (binding.rgModelGroup.checkedRadioButtonId) {
            binding.rbModelFlash20.id -> GeminiConstants.MODEL_3_8_LIVE
            binding.rbModelFlash15.id -> GeminiConstants.MODEL_3_1_FLASH_AGENT
            else -> GeminiConstants.MODEL_3_1_FLASH
        }

        try {
            preferences.apiKey = key
        } catch (e: IllegalStateException) {
            Snackbar.make(binding.root, e.message ?: "Secure key storage is unavailable.", Snackbar.LENGTH_LONG).show()
            return
        }
        preferences.aiModel = selectedModel
        preferences.customEndpoint = customEp
        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
        com.jarvis.assistant.background.JarvisConversationService.restartSession()

        Toast.makeText(this, "API & Connection settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
