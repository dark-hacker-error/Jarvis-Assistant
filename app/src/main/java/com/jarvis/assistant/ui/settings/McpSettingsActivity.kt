package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.ActivityMcpSettingsBinding
import com.jarvis.assistant.databinding.ItemMcpServerBinding
import com.jarvis.assistant.mcp.McpCustomServer
import com.jarvis.assistant.mcp.McpServer
import kotlinx.coroutines.launch

class McpSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMcpSettingsBinding
    private val mcp by lazy { JarvisApp.instance.mcpManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMcpSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddCustomMcp.setOnClickListener { showAddCustomServerDialog() }
        renderServers()
    }

    override fun onResume() {
        super.onResume()
        renderServers()
    }

    private fun renderServers() {
        binding.llBuiltInMcpServers.removeAllViews()
        mcp.builtInServers().forEach { server ->
            binding.llBuiltInMcpServers.addView(createServerCard(server, null))
        }

        binding.llCustomMcpServers.removeAllViews()
        val customServers = mcp.customServers()
        binding.tvCustomMcpEmpty.visibility = if (customServers.isEmpty()) View.VISIBLE else View.GONE
        customServers.forEach { custom ->
            val server = McpServer(
                id = custom.id,
                name = custom.name,
                description = "Custom MCP server • ${custom.url}",
                builtIn = false,
                requiresShizuku = false,
                tools = mcp.cachedTools(custom.id)
            )
            binding.llCustomMcpServers.addView(createServerCard(server, custom))
        }
        updateSummary()
    }

    private fun createServerCard(server: McpServer, custom: McpCustomServer?): View {
        val item = ItemMcpServerBinding.inflate(layoutInflater, binding.llBuiltInMcpServers, false)
        item.tvMcpName.text = server.name
        item.tvMcpStatus.text = if (custom != null) {
            "CUSTOM • ${server.tools.size} tools"
        } else {
            "${server.tools.size} tools • ${if (server.requiresShizuku) "Shizuku" else "On-device"}"
        }
        item.tvMcpDescription.text = if (custom != null) {
            "${server.description}\n${if (custom.token.isNotBlank()) "Bearer token configured" else "No bearer token"}"
        } else {
            server.description
        }
        item.tvMcpTools.text = if (server.tools.isEmpty()) {
            if (custom != null) "No tools discovered. Tap Refresh tools." else "No tools"
        } else {
            server.tools.joinToString("  •  ") { "${it.name} [${it.risk.label}]" }
        }

        item.switchMcpServer.setOnCheckedChangeListener(null)
        item.switchMcpServer.isChecked = if (custom != null) custom.enabled else mcp.isBuiltInEnabled(server.id)
        item.switchMcpServer.setOnCheckedChangeListener { _, checked ->
            if (custom != null) {
                mcp.setCustomEnabled(custom.id, checked)
                if (checked && server.tools.isEmpty()) {
                    refreshCustomServer(custom, showToast = true)
                } else {
                    updateSummary()
                }
            } else {
                mcp.setBuiltInEnabled(server.id, checked)
                updateSummary()
            }
        }

        if (custom != null) {
            item.layoutMcpCustomActions.visibility = View.VISIBLE
            item.btnMcpRefresh.setOnClickListener { refreshCustomServer(custom, showToast = true) }
            item.btnMcpDelete.setOnClickListener { confirmDelete(custom) }
        } else {
            item.layoutMcpCustomActions.visibility = View.GONE
        }
        return item.root
    }

    private fun updateSummary() {
        val servers = mcp.enabledServerCount()
        val tools = mcp.enabledToolCount()
        binding.tvMcpSummary.text = "$servers MCP(s) enabled • $tools tools available"
        binding.tvMcpSecurity.text = buildString {
            append("Only enabled tools are shared with Jarvis. Sensitive actions always ask for explicit confirmation.")
            if (!mcp.hasSecureStorage()) {
                append("\n\nWarning: encrypted storage is unavailable on this device. Custom server tokens will be stored in app-private preferences instead.")
            }
        }
        val shizukuReady = JarvisApp.instance.shizukuManager.isReady()
        if (!shizukuReady && servers > 0) {
            binding.tvMcpSecurity.append("\n\nShizuku is not ready. Tools marked Shizuku will return a permission error until you enable it in Settings → Shizuku System Access.")
        }
    }

    private fun refreshCustomServer(custom: McpCustomServer, showToast: Boolean) {
        lifecycleScope.launch {
            if (showToast) {
                Toast.makeText(this@McpSettingsActivity, "Refreshing ${custom.name}…", Toast.LENGTH_SHORT).show()
            }
            val result = mcp.refreshCustomServer(custom.id)
            renderServers()
            result.onSuccess { tools ->
                Toast.makeText(
                    this@McpSettingsActivity,
                    "${tools.size} tool(s) discovered on ${custom.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@McpSettingsActivity,
                    "MCP refresh failed: ${error.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showAddCustomServerDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Server name"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val urlInput = EditText(this).apply {
            hint = "https://example.com/mcp"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val tokenInput = EditText(this).apply {
            hint = "Bearer token (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(nameInput)
        container.addView(urlInput)
        container.addView(tokenInput)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Add Custom MCP")
            .setMessage("Jarvis uses Streamable HTTP JSON-RPC. HTTPS is required; HTTP is only accepted for localhost/127.0.0.1/::1.")
            .setView(container)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString()
                val url = urlInput.text.toString()
                val token = tokenInput.text.toString()
                mcp.addCustomServer(name, url, token).onSuccess { server ->
                    dialog.dismiss()
                    renderServers()
                    refreshCustomServer(server, showToast = true)
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "Could not add MCP server",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(custom: McpCustomServer) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove ${custom.name}?")
            .setMessage("This removes the server configuration and its cached tool list. It does not affect the remote server.")
            .setPositiveButton("Remove") { _, _ ->
                mcp.removeCustomServer(custom.id)
                renderServers()
                Toast.makeText(this, "Custom MCP removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
