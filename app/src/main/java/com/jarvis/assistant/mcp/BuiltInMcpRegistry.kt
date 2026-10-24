package com.jarvis.assistant.mcp

/**
 * Free, on-device MCP servers. They do not require an external account and
 * use Shizuku only for the operations that genuinely need system privilege.
 *
 * The list is deliberately declarative so the settings UI, prompt builder and
 * executor always agree about which tools exist.
 */
object BuiltInMcpRegistry {

    private fun tool(
        serverId: String,
        serverName: String,
        name: String,
        description: String,
        risk: McpRisk,
        requiresShizuku: Boolean,
        vararg parameters: McpParameter
    ): McpTool = McpTool(
        id = "$serverId:$name",
        name = name,
        serverId = serverId,
        serverName = serverName,
        description = description,
        parameters = parameters.toList(),
        risk = risk,
        requiresShizuku = requiresShizuku
    )

    val servers: List<McpServer> = listOf(
        McpServer(
            id = "system_control",
            name = "System Control",
            description = "Power, network toggles, brightness, volume, panels and a guarded shell.",
            builtIn = true,
            requiresShizuku = true,
            tools = listOf(
                tool(
                    "system_control", "System Control", "system_status",
                    "Read device model, Android version, battery, uptime and Shizuku status.",
                    McpRisk.SAFE, false
                ),
                tool(
                    "system_control", "System Control", "power",
                    "Reboot or switch off the device. Always requires spoken confirmation.",
                    McpRisk.DANGEROUS, true,
                    McpParameter("action", "reboot or shutdown", true)
                ),
                tool(
                    "system_control", "System Control", "system_setting",
                    "Change Wi-Fi, Bluetooth, airplane mode, Do Not Disturb or rotation.",
                    McpRisk.CONFIRM, true,
                    McpParameter("setting", "wifi, bluetooth, airplane_mode, dnd or rotation", true),
                    McpParameter("value", "on/off, auto, portrait, landscape or a numeric level", true)
                ),
                tool(
                    "system_control", "System Control", "volume",
                    "Set media, ring or alarm volume using the device audio manager.",
                    McpRisk.SAFE, false,
                    McpParameter("level", "Volume percentage from 0 to 100", true),
                    McpParameter("stream", "music, ring or alarm", false)
                ),
                tool(
                    "system_control", "System Control", "brightness",
                    "Set display brightness as a percentage using Shizuku.",
                    McpRisk.SAFE, true,
                    McpParameter("percent", "Brightness from 1 to 100", true)
                ),
                tool(
                    "system_control", "System Control", "open_panel",
                    "Open Settings, Wi-Fi, Bluetooth or battery settings.",
                    McpRisk.SAFE, false,
                    McpParameter("panel", "settings, wifi, bluetooth or battery", true)
                ),
                tool(
                    "system_control", "System Control", "shell",
                    "Run a guarded Android shell command with Shizuku. Requires explicit confirmation.",
                    McpRisk.DANGEROUS, true,
                    McpParameter("command", "Shell command to run", true),
                    McpParameter("timeout_ms", "Optional timeout in milliseconds, maximum 60000", false)
                )
            )
        ),
        McpServer(
            id = "app_manager",
            name = "App Manager",
            description = "Inspect installed apps, stop them, clear data or change enabled state.",
            builtIn = true,
            requiresShizuku = true,
            tools = listOf(
                tool(
                    "app_manager", "App Manager", "list_apps",
                    "List installed packages, optionally only third-party apps.",
                    McpRisk.SAFE, false,
                    McpParameter("filter", "all or third_party", false)
                ),
                tool(
                    "app_manager", "App Manager", "app_info",
                    "Read version, system-app status and install path for a package.",
                    McpRisk.SAFE, false,
                    McpParameter("package", "Android package name", true)
                ),
                tool(
                    "app_manager", "App Manager", "force_stop",
                    "Force-stop an app. Requires confirmation because unsaved work may be lost.",
                    McpRisk.CONFIRM, true,
                    McpParameter("package", "Android package name", true)
                ),
                tool(
                    "app_manager", "App Manager", "clear_data",
                    "Erase all app data and cache. This is irreversible.",
                    McpRisk.DANGEROUS, true,
                    McpParameter("package", "Android package name", true)
                ),
                tool(
                    "app_manager", "App Manager", "disable",
                    "Disable a user-installed app. System apps are rejected.",
                    McpRisk.DANGEROUS, true,
                    McpParameter("package", "Android package name", true)
                ),
                tool(
                    "app_manager", "App Manager", "enable",
                    "Re-enable a disabled app.",
                    McpRisk.CONFIRM, true,
                    McpParameter("package", "Android package name", true)
                )
            )
        ),
        McpServer(
            id = "file_manager",
            name = "File Manager",
            description = "Read or delete files in shared storage with strict path guards.",
            builtIn = true,
            requiresShizuku = true,
            tools = listOf(
                tool(
                    "file_manager", "File Manager", "list_files",
                    "List a folder under /sdcard or /storage/emulated/0.",
                    McpRisk.CONFIRM, true,
                    McpParameter("path", "Absolute shared-storage path", true)
                ),
                tool(
                    "file_manager", "File Manager", "delete_path",
                    "Permanently delete a shared-storage file or folder with rm -rf. Irreversible.",
                    McpRisk.DANGEROUS, true,
                    McpParameter("path", "Absolute shared-storage path", true)
                ),
                tool(
                    "file_manager", "File Manager", "disk_usage",
                    "Show internal and shared storage usage.",
                    McpRisk.SAFE, false
                )
            )
        ),
        McpServer(
            id = "network_tools",
            name = "Network Tools",
            description = "Check connectivity, ping hosts, inspect Wi-Fi and resolve DNS.",
            builtIn = true,
            requiresShizuku = true,
            tools = listOf(
                tool(
                    "network_tools", "Network Tools", "network_status",
                    "Show active network type, validation and link speed.",
                    McpRisk.SAFE, false
                ),
                tool(
                    "network_tools", "Network Tools", "ping",
                    "Ping a host three times with Shizuku.",
                    McpRisk.SAFE, true,
                    McpParameter("host", "Hostname or IP address", true)
                ),
                tool(
                    "network_tools", "Network Tools", "wifi_status",
                    "Read the current Wi-Fi status using Android shell.",
                    McpRisk.SAFE, true
                ),
                tool(
                    "network_tools", "Network Tools", "dns_lookup",
                    "Resolve a hostname to IPv4/IPv6 addresses.",
                    McpRisk.SAFE, false,
                    McpParameter("host", "Hostname to resolve", true)
                )
            )
        ),
        McpServer(
            id = "device_insights",
            name = "Device Insights",
            description = "Battery, storage, uptime and hardware information without changing anything.",
            builtIn = true,
            requiresShizuku = false,
            tools = listOf(
                tool(
                    "device_insights", "Device Insights", "battery_status",
                    "Read battery percentage, charging state and power state.",
                    McpRisk.SAFE, false
                ),
                tool(
                    "device_insights", "Device Insights", "storage_status",
                    "Read internal and shared storage capacity.",
                    McpRisk.SAFE, false
                ),
                tool(
                    "device_insights", "Device Insights", "uptime",
                    "Read how long the device has been running.",
                    McpRisk.SAFE, false
                ),
                tool(
                    "device_insights", "Device Insights", "device_info",
                    "Read manufacturer, model, Android version and SDK level.",
                    McpRisk.SAFE, false
                )
            )
        )
    )

    private val toolsById: Map<String, McpTool> =
        servers.flatMap { it.tools }.associateBy { it.id }

    private val toolsByName: Map<String, List<McpTool>> =
        servers.flatMap { it.tools }.groupBy { it.name }

    fun findServer(serverId: String): McpServer? = servers.firstOrNull { it.id == serverId }

    fun findTool(toolId: String): McpTool? = toolsById[toolId]

    fun findToolByName(name: String): McpTool? =
        toolsByName[name]?.takeIf { it.size == 1 }?.firstOrNull()

    fun allTools(): List<McpTool> = servers.flatMap { it.tools }
}
