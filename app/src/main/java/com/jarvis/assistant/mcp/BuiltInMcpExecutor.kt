package com.jarvis.assistant.mcp

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.jarvis.assistant.system.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Executes the free built-in MCP tools. Every destructive operation has already
 * passed through [McpManager]'s risk/confirmation gate; this class re-validates
 * arguments before it builds a shell command and never evaluates free-form
 * arguments as part of a shell string without quoting.
 */
class BuiltInMcpExecutor(
    private val context: Context,
    private val shizuku: ShizukuManager
) {

    fun validate(tool: McpTool, args: JSONObject): String? {
        return when (tool.name) {
            "power" -> {
                val action = args.optString("action").trim().lowercase(Locale.ROOT)
                if (action !in setOf("reboot", "shutdown", "poweroff", "power_off")) {
                    "action must be 'reboot' or 'shutdown'"
                } else {
                    null
                }
            }
            "system_setting" -> {
                val setting = args.optString("setting").trim().lowercase(Locale.ROOT)
                val value = args.optString("value").trim().lowercase(Locale.ROOT)
                when {
                    setting !in setOf("wifi", "bluetooth", "airplane_mode", "dnd", "rotation") ->
                        "setting must be wifi, bluetooth, airplane_mode, dnd or rotation"
                    value.isBlank() -> "value is required"
                    setting == "rotation" && value !in setOf("on", "off", "auto", "portrait", "landscape") ->
                        "rotation value must be auto, portrait, landscape, on or off"
                    setting != "rotation" && value !in setOf("on", "off", "enable", "disable", "1", "0", "true", "false") ->
                        "value must be on/off for $setting"
                    else -> null
                }
            }
            "brightness" -> {
                val percent = args.optInt("percent", -1)
                if (percent !in 1..100) "percent must be between 1 and 100" else null
            }
            "volume" -> {
                val level = args.optInt("level", -1)
                val stream = args.optString("stream", "music").lowercase(Locale.ROOT)
                when {
                    level !in 0..100 -> "level must be between 0 and 100"
                    stream !in setOf("music", "ring", "alarm") -> "stream must be music, ring or alarm"
                    else -> null
                }
            }
            "open_panel" -> {
                val panel = args.optString("panel").trim().lowercase(Locale.ROOT)
                if (panel !in setOf("settings", "wifi", "bluetooth", "battery")) {
                    "panel must be settings, wifi, bluetooth or battery"
                } else {
                    null
                }
            }
            "shell" -> validateShellCommand(args.optString("command"))
            "force_stop", "clear_data", "disable", "enable", "app_info" -> {
                val packageName = args.optString("package").trim()
                when {
                    !PACKAGE_REGEX.matches(packageName) -> "A valid Android package name is required"
                    tool.name in setOf("clear_data", "disable") && isSystemPackage(packageName) ->
                        "Refusing to ${tool.name.replace('_', ' ')} a system package ($packageName)"
                    else -> null
                }
            }
            "list_apps" -> {
                val filter = args.optString("filter", "all").trim().lowercase(Locale.ROOT)
                if (filter !in setOf("all", "third_party")) {
                    "filter must be 'all' or 'third_party'"
                } else {
                    null
                }
            }
            "list_files" -> validateSharedPath(args.optString("path"), allowRoot = true)
            "delete_path" -> validateSharedPath(args.optString("path"), allowRoot = false)
            "ping", "dns_lookup" -> validateHost(args.optString("host"))
            else -> null
        }
    }

    suspend fun execute(tool: McpTool, args: JSONObject): McpExecutionResult {
        validate(tool, args)?.let { return McpExecutionResult.Failure(it) }

        return try {
            when (tool.name) {
                "system_status" -> systemStatus()
                "power" -> power(args)
                "system_setting" -> systemSetting(args)
                "volume" -> volume(args)
                "brightness" -> brightness(args)
                "open_panel" -> openPanel(args)
                "shell" -> runShell(
                    args.optString("command"),
                    args.optInt("timeout_ms", 30_000),
                    "Shell command completed."
                )
                "list_apps" -> listApps(args)
                "app_info" -> appInfo(args)
                "force_stop" -> forceStop(args)
                "clear_data" -> clearData(args)
                "disable" -> disableApp(args)
                "enable" -> enableApp(args)
                "list_files" -> listFiles(args)
                "delete_path" -> deletePath(args)
                "disk_usage" -> diskUsage()
                "network_status" -> networkStatus()
                "ping" -> ping(args)
                "wifi_status" -> wifiStatus()
                "dns_lookup" -> dnsLookup(args)
                "battery_status" -> batteryStatus()
                "storage_status" -> storageStatus()
                "uptime" -> uptime()
                "device_info" -> deviceInfo()
                else -> McpExecutionResult.Failure("Unknown built-in tool: ${tool.name}")
            }
        } catch (e: Exception) {
            McpExecutionResult.Failure("Tool failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun systemStatus(): McpExecutionResult {
        val battery = batteryValues()
        val lines = listOf(
            "Manufacturer: ${Build.MANUFACTURER}",
            "Model: ${Build.MODEL}",
            "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "Battery: ${battery.percent}%${if (battery.charging) " • charging" else ""}",
            "Uptime: ${formatDuration(SystemClock.elapsedRealtime())}",
            "Shizuku: ${shizuku.statusText()}"
        )
        return McpExecutionResult.Success(
            message = "Device status ready.",
            output = lines.joinToString("\n")
        )
    }

    private suspend fun power(args: JSONObject): McpExecutionResult {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        val command = if (action == "reboot") {
            "svc power reboot || reboot"
        } else {
            "svc power shutdown || reboot -p"
        }
        val shell = shizuku.runShell(command, 20_000)
        return if (shell.success) {
            McpExecutionResult.Success(
                message = if (action == "reboot") "Reboot command accepted." else "Power-off command accepted.",
                output = shell.output
            )
        } else {
            McpExecutionResult.Failure(
                shell.error.ifBlank { "The device rejected the power command. This may require a rooted Shizuku session." }
            )
        }
    }

    private suspend fun systemSetting(args: JSONObject): McpExecutionResult {
        val setting = args.optString("setting").lowercase(Locale.ROOT)
        val value = args.optString("value").lowercase(Locale.ROOT)
        val enable = value in setOf("on", "enable", "1", "true")
        val command = when (setting) {
            "wifi" -> "svc wifi ${if (enable) "enable" else "disable"} 2>/dev/null || " +
                "cmd wifi set-wifi-enabled ${if (enable) "enabled" else "disabled"}"
            "bluetooth" -> "svc bluetooth ${if (enable) "enable" else "disable"} 2>/dev/null || " +
                "cmd bluetooth_manager ${if (enable) "enable" else "disable"}"
            "airplane_mode" -> "cmd connectivity airplane-mode ${if (enable) "enable" else "disable"}"
            "dnd" -> "settings put global zen_mode ${if (enable) "1" else "0"}"
            "rotation" -> when (value) {
                "auto", "on" -> "settings put system accelerometer_rotation 1"
                "portrait" -> "settings put system accelerometer_rotation 0; settings put system user_rotation 0"
                "landscape" -> "settings put system accelerometer_rotation 0; settings put system user_rotation 1"
                else -> "settings put system accelerometer_rotation 0"
            }
            else -> return McpExecutionResult.Failure("Unsupported system setting: $setting")
        }
        return runShell(command, 15_000, "System setting '$setting' updated.")
    }

    private fun volume(args: JSONObject): McpExecutionResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return McpExecutionResult.Failure("Audio service is unavailable")
        if (audio.isVolumeFixed) {
            return McpExecutionResult.Failure("This device reports a fixed volume")
        }
        val stream = when (args.optString("stream", "music").lowercase(Locale.ROOT)) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val level = args.optInt("level", -1).coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(stream)
        val target = ((level / 100f) * max).roundToInt().coerceIn(0, max)
        return try {
            audio.setStreamVolume(stream, target, 0)
            McpExecutionResult.Success("Volume set to $level%.")
        } catch (e: SecurityException) {
            McpExecutionResult.Failure("Volume permission was denied: ${e.message}")
        }
    }

    private suspend fun brightness(args: JSONObject): McpExecutionResult {
        val percent = args.optInt("percent", -1).coerceIn(1, 100)
        val raw = ((percent / 100f) * 255f).roundToInt().coerceIn(1, 255)
        return runShell(
            "settings put system screen_brightness_mode 0; settings put system screen_brightness $raw",
            15_000,
            "Brightness set to $percent%."
        )
    }

    private fun openPanel(args: JSONObject): McpExecutionResult {
        val action = when (args.optString("panel").lowercase(Locale.ROOT)) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            McpExecutionResult.Success("Settings panel opened.")
        } catch (e: Exception) {
            McpExecutionResult.Failure("Could not open settings panel: ${e.message}")
        }
    }

    private suspend fun runShell(
        command: String,
        timeoutMs: Int,
        successMessage: String
    ): McpExecutionResult {
        val result = shizuku.runShell(command, timeoutMs)
        if (!result.success) {
            return McpExecutionResult.Failure(
                buildString {
                    append(result.error.ifBlank { "Shell command failed" })
                    if (result.output.isNotBlank()) {
                        append("\n")
                        append(result.output.take(4_000))
                    }
                }
            )
        }
        return McpExecutionResult.Success(successMessage, result.output.take(8_000))
    }

    private fun listApps(args: JSONObject): McpExecutionResult {
        val thirdPartyOnly = args.optString("filter", "all").equals("third_party", ignoreCase = true)
        val packages = context.packageManager.getInstalledPackages(0)
            .asSequence()
            .filter { pkg ->
                !thirdPartyOnly ||
                    (((pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) == 0)
            }
            .map { it.packageName }
            .sorted()
            .take(200)
            .toList()
        return McpExecutionResult.Success(
            "Found ${packages.size} package(s).",
            packages.joinToString("\n")
        )
    }

    private fun appInfo(args: JSONObject): McpExecutionResult {
        val packageName = args.optString("package").trim()
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            val appInfo = info.applicationInfo
            val system = ((appInfo?.flags ?: 0) and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            val lines = listOf(
                "Package: $packageName",
                "Version: ${info.versionName ?: "unknown"} (${PackageInfoCompat.getLongVersionCode(info)})",
                "System app: $system",
                "Enabled: ${appInfo?.enabled ?: false}",
                "APK: ${appInfo?.sourceDir ?: "unknown"}"
            )
            McpExecutionResult.Success("App information loaded.", lines.joinToString("\n"))
        } catch (_: PackageManager.NameNotFoundException) {
            McpExecutionResult.Failure("App '$packageName' is not installed")
        }
    }

    private suspend fun forceStop(args: JSONObject): McpExecutionResult {
        val packageName = args.optString("package").trim()
        return runShell("am force-stop ${shellQuote(packageName)}", 15_000, "App '$packageName' force-stopped.")
    }

    private suspend fun clearData(args: JSONObject): McpExecutionResult {
        val packageName = args.optString("package").trim()
        return runShell("pm clear --user 0 ${shellQuote(packageName)}", 30_000, "All data cleared for '$packageName'.")
    }

    private suspend fun disableApp(args: JSONObject): McpExecutionResult {
        val packageName = args.optString("package").trim()
        return runShell(
            "pm disable-user --user 0 ${shellQuote(packageName)}",
            20_000,
            "App '$packageName' disabled."
        )
    }

    private suspend fun enableApp(args: JSONObject): McpExecutionResult {
        val packageName = args.optString("package").trim()
        return runShell("pm enable ${shellQuote(packageName)}", 20_000, "App '$packageName' enabled.")
    }

    private suspend fun listFiles(args: JSONObject): McpExecutionResult {
        val path = normalizePath(args.optString("path"))
        return runShell("ls -la -- ${shellQuote(path)}", 15_000, "Listed $path.")
    }

    private suspend fun deletePath(args: JSONObject): McpExecutionResult {
        val path = normalizePath(args.optString("path"))
        return runShell("rm -rf -- ${shellQuote(path)}", 30_000, "Deleted $path.")
    }

    private fun diskUsage(): McpExecutionResult {
        val data = StatFs(Environment.getDataDirectory().path)
        val external = try {
            StatFs(Environment.getExternalStorageDirectory().path)
        } catch (_: Exception) {
            null
        }
        return McpExecutionResult.Success(
            "Storage usage ready.",
            buildString {
                append("Internal: ")
                append(formatBytes(data.availableBytes))
                append(" free of ")
                append(formatBytes(data.totalBytes))
                if (external != null) {
                    append("\nShared storage: ")
                    append(formatBytes(external.availableBytes))
                    append(" free of ")
                    append(formatBytes(external.totalBytes))
                }
            }
        )
    }

    private fun networkStatus(): McpExecutionResult {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return McpExecutionResult.Failure("Connectivity service is unavailable")
        val network = manager.activeNetwork
            ?: return McpExecutionResult.Success("No active network.", "No active network")
        val caps = manager.getNetworkCapabilities(network)
        val link = manager.getLinkProperties(network)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("Cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        }.ifEmpty { listOf("Unknown") }
        val lines = listOf(
            "Transport: ${transports.joinToString(", ")}",
            "Validated: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}",
            "Downstream: ${caps?.linkDownstreamBandwidthKbps ?: 0} kbps",
            "Upstream: ${caps?.linkUpstreamBandwidthKbps ?: 0} kbps",
            "Interface: ${link?.interfaceName ?: "unknown"}"
        )
        return McpExecutionResult.Success("Network status ready.", lines.joinToString("\n"))
    }

    private suspend fun ping(args: JSONObject): McpExecutionResult {
        val host = args.optString("host").trim()
        return runShell("ping -c 3 -W 2 ${shellQuote(host)}", 15_000, "Ping completed for $host.")
    }

    private suspend fun wifiStatus(): McpExecutionResult {
        return runShell(
            "cmd wifi status 2>/dev/null || dumpsys wifi | head -80",
            15_000,
            "Wi-Fi status ready."
        )
    }

    private suspend fun dnsLookup(args: JSONObject): McpExecutionResult = withContext(Dispatchers.IO) {
        val host = args.optString("host").trim()
        try {
            val addresses = InetAddress.getAllByName(host)
            McpExecutionResult.Success(
                "Resolved ${addresses.size} address(es) for $host.",
                addresses.joinToString("\n") { it.hostAddress ?: it.toString() }
            )
        } catch (e: Exception) {
            McpExecutionResult.Failure("DNS lookup failed: ${e.message}")
        }
    }

    private fun batteryStatus(): McpExecutionResult {
        val values = batteryValues()
        return McpExecutionResult.Success(
            "Battery status ready.",
            "Level: ${values.percent}%\nCharging: ${values.charging}\nPower save: ${values.powerSave}"
        )
    }

    private fun storageStatus(): McpExecutionResult = diskUsage()

    private fun uptime(): McpExecutionResult = McpExecutionResult.Success(
        "Uptime ready.",
        "Device uptime: ${formatDuration(SystemClock.elapsedRealtime())}"
    )

    private fun deviceInfo(): McpExecutionResult = McpExecutionResult.Success(
        "Device information ready.",
        "Manufacturer: ${Build.MANUFACTURER}\nModel: ${Build.MODEL}\nDevice: ${Build.DEVICE}\nAndroid: ${Build.VERSION.RELEASE}\nSDK: ${Build.VERSION.SDK_INT}"
    )

    private data class BatteryValues(val percent: Int, val charging: Boolean, val powerSave: Boolean)

    private fun batteryValues(): BatteryValues {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging == true
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return BatteryValues(
            percent = percent.coerceAtLeast(0),
            charging = charging,
            powerSave = power?.isPowerSaveMode == true
        )
    }

    private fun isSystemPackage(packageName: String): Boolean = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    } catch (_: Exception) {
        false
    }

    private fun validateHost(host: String): String? {
        val clean = host.trim()
        if (clean.length !in 1..253) return "A valid hostname or IP address is required"
        if (!HOST_REGEX.matches(clean)) return "Host may only contain letters, numbers, dots, hyphens and colons"
        if (clean.startsWith("-") || clean.startsWith(".") || clean.endsWith(".")) {
            return "Invalid hostname"
        }
        return null
    }

    private fun validateSharedPath(path: String, allowRoot: Boolean): String? {
        val normalized = normalizePath(path)
        val root = when {
            normalized == "/sdcard" || normalized.startsWith("/sdcard/") -> "/sdcard"
            normalized == "/storage/emulated/0" || normalized.startsWith("/storage/emulated/0/") ->
                "/storage/emulated/0"
            else -> return "Path must be inside /sdcard or /storage/emulated/0"
        }
        if (normalized.split('/').any { it == ".." }) return "Parent-directory traversal is not allowed"
        if (!allowRoot && normalized == root) return "Refusing to delete the shared-storage root"
        if (normalized.length > 500) return "Path is too long"

        // Resolve symlinks before allowing a delete. A symlink placed inside
        // shared storage must not become a path into /data or another private
        // location when rm runs with Shizuku privileges.
        val canonicalPath = try {
            File(normalized).canonicalPath
        } catch (_: Exception) {
            return "Could not resolve the path safely"
        }
        val canonicalRoot = try {
            File(root).canonicalPath
        } catch (_: Exception) {
            return "Could not resolve the shared-storage root safely"
        }
        if (canonicalPath != canonicalRoot && !canonicalPath.startsWith("$canonicalRoot/")) {
            return "Path resolves outside shared storage"
        }
        return null
    }

    private fun normalizePath(path: String): String =
        path.trim().replace('\\', '/').replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }

    private fun validateShellCommand(command: String): String? {
        val clean = command.trim()
        if (clean.isBlank()) return "command is required"
        if (clean.length > 2_000) return "command is too long"
        val lower = clean.lowercase(Locale.ROOT)
        if (DESTRUCTIVE_ROOT_COMMAND.containsMatchIn(lower)) {
            return "Refusing to run a destructive command against a system or storage root"
        }
        if (GENERIC_POWER_COMMAND.containsMatchIn(lower)) {
            return "Use the system_control:power tool for reboot or shutdown"
        }
        if (BLOCK_DEVICE_WRITE.containsMatchIn(lower)) {
            return "Refusing to write directly to a block device from the guarded shell"
        }
        return null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    private companion object {
        val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val HOST_REGEX = Regex("^[A-Za-z0-9.:-]+$")
        val DESTRUCTIVE_ROOT_COMMAND = Regex(
            """\brm\s+(?:-[a-zA-Z]*[rf][a-zA-Z]*\s+)+(?:["']?)(?:/|/system|/data|/vendor|/product|/odm|/sdcard|/storage/emulated/0)/?(?:\s|$|["'])"""
        )
        val GENERIC_POWER_COMMAND = Regex("""\b(reboot|shutdown|poweroff)\b""")
        val BLOCK_DEVICE_WRITE = Regex("""\bdd\b.*\bof=/dev/(?:block|mmcblk|sda)""")
    }
}
