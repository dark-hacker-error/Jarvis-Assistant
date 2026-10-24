package com.jarvis.assistant.ui.settings

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.ActivityShizukuSettingsBinding
import com.jarvis.assistant.system.ShizukuManager
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class ShizukuSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShizukuSettingsBinding
    private val shizuku by lazy { JarvisApp.instance.shizukuManager }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuManager.PERMISSION_REQUEST_CODE) {
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Shizuku permission granted", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        val ready = shizuku.ensureUserService()
                        updateStatus()
                        if (!ready) {
                            Toast.makeText(
                                this@ShizukuSettingsActivity,
                                "Permission mil gayi, lekin privileged shell service abhi ready nahi hai. Shizuku running hai na?",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    updateStatus()
                    Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityShizukuSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRequestShizuku.setOnClickListener { requestOrBind() }
        binding.btnOpenShizuku.setOnClickListener {
            if (!shizuku.openShizukuManager()) {
                Toast.makeText(this, "Shizuku open nahi ho saka", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnTestShizuku.setOnClickListener { testShell() }

        try {
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        } catch (_: Exception) {
            // Shizuku may not be installed; the status card explains what to do.
        }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun requestOrBind() {
        when {
            !shizuku.isShizukuInstalled() -> {
                shizuku.openShizukuDownloadPage()
                Toast.makeText(this, "Pehle Shizuku install karein", Toast.LENGTH_LONG).show()
            }
            !shizuku.isBinderAvailable() -> {
                shizuku.openShizukuManager()
                Toast.makeText(this, "Shizuku service start karein, phir yahan wapas aayein", Toast.LENGTH_LONG).show()
            }
            shizuku.hasPermission() -> {
                lifecycleScope.launch {
                    binding.tvShizukuTestResult.visibility = View.VISIBLE
                    binding.tvShizukuTestResult.text = "Binding privileged shell service…"
                    val ready = shizuku.ensureUserService()
                    updateStatus()
                    binding.tvShizukuTestResult.text = if (ready) {
                        "Privileged shell service ready."
                    } else {
                        "Could not bind the privileged shell service. Shizuku ko open karke service running rakhein."
                    }
                }
            }
            else -> {
                val requested = shizuku.requestPermission()
                if (requested) {
                    Toast.makeText(this, "Shizuku permission request bheja gaya", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Permission prompt nahi khul saka. Shizuku app me Jarvis ko manually allow karein.",
                        Toast.LENGTH_LONG
                    ).show()
                    shizuku.openShizukuManager()
                }
            }
        }
    }

    private fun testShell() {
        if (!shizuku.hasPermission()) {
            Toast.makeText(this, "Pehle Shizuku permission grant karein", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            binding.tvShizukuTestResult.visibility = View.VISIBLE
            binding.tvShizukuTestResult.text = "Running: id && getprop ro.build.version.release"
            val result = shizuku.runShell("id; getprop ro.build.version.release")
            binding.tvShizukuTestResult.text = if (result.success) {
                buildString {
                    append("Shell OK")
                    if (result.output.isNotBlank()) append("\n${result.output.trim()}")
                }
            } else {
                buildString {
                    append("Shell failed")
                    if (result.error.isNotBlank()) append("\n${result.error}")
                    if (result.output.isNotBlank()) append("\n${result.output.trim()}")
                }
            }
            updateStatus()
        }
    }

    private fun updateStatus() {
        val installed = shizuku.isShizukuInstalled()
        val binder = shizuku.isBinderAvailable()
        val permission = shizuku.hasPermission()
        val ready = shizuku.isReady()

        binding.tvShizukuStatus.text = shizuku.statusText()
        binding.tvShizukuDetails.text = shizuku.statusDetails()

        val color = when {
            ready -> "#10B981"
            installed && binder -> "#EAB308"
            else -> "#EF4444"
        }
        binding.viewShizukuStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))

        binding.btnRequestShizuku.text = when {
            !installed -> "Install Shizuku"
            !binder -> "Open Shizuku to Start Service"
            !permission -> "Request Shizuku Permission"
            else -> "Re-check / Bind Privileged Service"
        }
        binding.btnOpenShizuku.text = if (installed) "Open Shizuku" else "Install Shizuku"
        binding.btnTestShizuku.isEnabled = permission
        binding.btnTestShizuku.alpha = if (permission) 1f else 0.45f
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
        } catch (_: Exception) {
            // Nothing to unregister when Shizuku is absent.
        }
        super.onDestroy()
    }
}
