package com.jarvis.assistant.mcp

import org.json.JSONObject

enum class McpRisk(val label: String) {
    SAFE("SAFE"),
    CONFIRM("CONFIRM"),
    DANGEROUS("DANGEROUS")
}

data class McpParameter(
    val name: String,
    val description: String,
    val required: Boolean = true
)

data class McpTool(
    val id: String,
    val name: String,
    val serverId: String,
    val serverName: String,
    val description: String,
    val parameters: List<McpParameter> = emptyList(),
    val risk: McpRisk = McpRisk.SAFE,
    val requiresShizuku: Boolean = false,
    val argumentsHint: String = ""
) {
    fun argumentsPrompt(): String = if (parameters.isEmpty()) {
        "{}"
    } else {
        parameters.joinToString(", ", prefix = "{", postfix = "}") { parameter ->
            "\"${parameter.name}\": ${if (parameter.required) "required" else "optional"}"
        }
    }
}

data class McpServer(
    val id: String,
    val name: String,
    val description: String,
    val builtIn: Boolean,
    val requiresShizuku: Boolean,
    val tools: List<McpTool> = emptyList()
)

data class McpCustomServer(
    val id: String,
    val name: String,
    val url: String,
    val token: String = "",
    val enabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class McpRemoteTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject? = null
)

sealed class McpExecutionResult {
    data class Success(
        val message: String,
        val output: String = ""
    ) : McpExecutionResult()

    data class Failure(
        val message: String,
        val permissionRequired: Boolean = false
    ) : McpExecutionResult()
}

fun JSONObject.prettyArguments(maxLength: Int = 500): String {
    val text = toString()
    return if (text.length <= maxLength) text else text.take(maxLength) + "…"
}
