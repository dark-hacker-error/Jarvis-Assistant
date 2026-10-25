package com.jarvis.assistant.mcp

import android.util.Log
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal MCP Streamable-HTTP JSON-RPC client.
 *
 * Each operation initializes a session, sends the required
 * `notifications/initialized`, then performs `tools/list` or `tools/call`.
 * That is a little more chatty than a persistent session, but it works with
 * public and self-hosted HTTP MCP servers without keeping a background socket
 * alive on the phone.
 *
 * Reliability rules:
 *  - `initialize` and `tools/list` are retried a bounded number of times on
 *    transient network/5xx failures because they are idempotent;
 *  - `tools/call` is never retried automatically because it may have side
 *    effects;
 *  - the negotiated protocol version is echoed in later requests;
 *  - SSE events are matched to the JSON-RPC request id instead of blindly
 *    taking the last message on the stream.
 */
class McpHttpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        // Do not follow redirects automatically: a custom endpoint must not be
        // able to bounce a request (and its bearer token) to another host.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class Session(
        val sessionId: String?,
        val initializeResult: JSONObject?,
        val protocolVersion: String = PROTOCOL_VERSION
    )

    suspend fun listTools(server: McpCustomServer): List<McpRemoteTool> = withContext(Dispatchers.IO) {
        val session = initialize(server)
        val discovered = mutableListOf<McpRemoteTool>()
        var cursor: String? = null
        var page = 0
        do {
            val params = JSONObject()
            if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
            val requestId = 2 + page
            val envelope = withRetry("tools/list") {
                request(
                    server = server,
                    body = jsonRpcRequest(
                        id = requestId,
                        method = "tools/list",
                        params = params
                    ),
                    sessionId = session.sessionId,
                    protocolVersion = session.protocolVersion
                )
            }
            val result = requireResult(envelope, "tools/list")
            val tools = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until tools.length()) {
                if (discovered.size >= MAX_TOOLS) break
                val item = tools.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (!TOOL_NAME_REGEX.matches(name)) continue
                val description = item.optString("description")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_DESCRIPTION_CHARS)
                    .ifBlank { "Custom MCP tool $name" }
                discovered += McpRemoteTool(
                    name = name,
                    description = description,
                    inputSchema = item.optJSONObject("inputSchema")
                )
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            page++
        } while (!cursor.isNullOrBlank() && page < MAX_TOOL_PAGES)

        discovered.distinctBy { it.name }
    }

    suspend fun callTool(
        server: McpCustomServer,
        toolName: String,
        arguments: JSONObject
    ): String = withContext(Dispatchers.IO) {
        val session = initialize(server)
        val envelope = request(
            server = server,
            body = jsonRpcRequest(
                id = 10,
                method = "tools/call",
                params = JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                }
            ),
            sessionId = session.sessionId,
            protocolVersion = session.protocolVersion
        )
        val result = requireResult(envelope, "tools/call")
        if (result.optBoolean("isError", false)) {
            throw IOException(extractText(result).ifBlank { "MCP server reported a tool error" })
        }
        extractText(result)
    }

    private fun initialize(server: McpCustomServer): Session {
        var lastError: IOException? = null
        for (version in CLIENT_PROTOCOL_VERSIONS) {
            try {
                val envelope = withRetry("initialize") {
                    request(
                        server = server,
                        body = jsonRpcRequest(
                            id = 1,
                            method = "initialize",
                            params = JSONObject().apply {
                                put("protocolVersion", version)
                                put("capabilities", JSONObject())
                                put("clientInfo", JSONObject().apply {
                                    put("name", "Jarvis")
                                    put("version", BuildConfig.VERSION_NAME)
                                })
                            }
                        ),
                        sessionId = null,
                        protocolVersion = version
                    )
                }
                val result = requireResult(envelope, "initialize")
                val negotiated = result.optString("protocolVersion")
                    .takeIf { it.isNotBlank() } ?: version
                val sessionId = envelope.optString("__session_id").takeIf { it.isNotBlank() }

                // Required by the MCP lifecycle. A few older servers ignore it; a
                // 202 or empty body is therefore treated as success.
                try {
                    requestRaw(
                        server = server,
                        body = JSONObject().apply {
                            put("jsonrpc", "2.0")
                            put("method", "notifications/initialized")
                        },
                        sessionId = sessionId,
                        protocolVersion = negotiated
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "MCP notifications/initialized was not acknowledged", e)
                }

                return Session(sessionId, result, negotiated)
            } catch (e: IOException) {
                lastError = e
                if (!looksLikeProtocolMismatch(e)) throw e
                Log.i(TAG, "MCP server rejected protocol $version; trying the next supported version", e)
            }
        }
        throw lastError ?: IOException("MCP initialize failed")
    }

    private fun request(
        server: McpCustomServer,
        body: JSONObject,
        sessionId: String?,
        protocolVersion: String
    ): JSONObject {
        val expectedId = body.optInt("id", Int.MIN_VALUE)
        val raw = requestRaw(server, body, sessionId, protocolVersion)
        val parsed = parseJsonPayload(raw.body, expectedId)
            ?: throw IOException("MCP server returned an empty or non-JSON response")
        val error = parsed.optJSONObject("error")
        if (error != null) {
            val code = error.optInt("code")
            val message = error.optString("message").ifBlank { "Unknown error" }
            val data = error.opt("data")?.toString()?.take(300).orEmpty()
            throw IOException(
                "MCP error $code: $message${if (data.isNotBlank()) " ($data)" else ""}"
            )
        }
        if (!parsed.has("result")) {
            throw IOException("MCP response does not contain a result")
        }
        parsed.put("__session_id", raw.sessionId.orEmpty())
        return parsed
    }

    private fun requestRaw(
        server: McpCustomServer,
        body: JSONObject,
        sessionId: String?,
        protocolVersion: String
    ): RawResponse {
        val builder = Request.Builder()
            .url(server.url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", protocolVersion)
            .post(body.toString().toRequestBody(jsonMediaType))
        if (server.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${server.token.trim()}")
        }
        if (!sessionId.isNullOrBlank()) {
            builder.header("Mcp-Session-Id", sessionId)
        }

        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body
            if (responseBody != null && responseBody.contentLength() > MAX_RESPONSE_BYTES) {
                throw IOException("MCP response is too large")
            }
            val text = responseBody?.string().orEmpty()
            if (text.length > MAX_RESPONSE_CHARS) {
                throw IOException("MCP response is too large")
            }
            if (!response.isSuccessful) {
                val detail = text.take(500).replace("\n", " ").trim()
                throw IOException(httpErrorMessage(response.code, detail))
            }
            return RawResponse(
                body = text,
                sessionId = response.header("Mcp-Session-Id")
                    ?: response.header("MCP-Session-Id")
                    ?: sessionId
            )
        }
    }

    private fun httpErrorMessage(code: Int, detail: String): String {
        val base = when (code) {
            401, 403 -> "MCP server rejected the bearer token (HTTP $code). Check the token in Settings > MCP Tools."
            404 -> "MCP endpoint was not found (HTTP 404). Check the server URL."
            405 -> "MCP server does not allow POST at this URL (HTTP 405)."
            429 -> "MCP server is rate-limiting requests (HTTP 429). Try again later."
            in 500..599 -> "MCP server error (HTTP $code)."
            else -> "MCP HTTP $code."
        }
        return if (detail.isBlank()) base else "$base $detail"
    }

    private fun <T> withRetry(operation: String, attempts: Int = 2, block: () -> T): T {
        var last: IOException? = null
        for (attempt in 1..attempts) {
            try {
                return block()
            } catch (e: IOException) {
                last = e
                if (attempt >= attempts || !isRetryable(e)) throw e
                Log.w(TAG, "MCP $operation failed on attempt $attempt/$attempts; retrying", e)
                try {
                    Thread.sleep(300L * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw last ?: IOException("MCP $operation failed")
    }

    private fun isRetryable(error: IOException): Boolean {
        val message = error.message.orEmpty()
        if (message.startsWith("MCP HTTP 4")) return false
        if (message.startsWith("MCP error")) return false
        if (message.contains("rejected the bearer token", ignoreCase = true)) return false
        if (message.contains("not valid JSON", ignoreCase = true)) return false
        return true
    }

    private fun looksLikeProtocolMismatch(error: IOException): Boolean {
        val message = error.message.orEmpty().lowercase()
        return message.contains("protocol") || message.contains("version") ||
            message.contains("unsupported")
    }

    private fun requireResult(envelope: JSONObject, operation: String): JSONObject {
        val result = envelope.optJSONObject("result")
            ?: throw IOException("MCP $operation returned no result object")
        return result
    }

    private fun jsonRpcRequest(id: Int, method: String, params: JSONObject): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

    private fun parseJsonPayload(text: String, expectedId: Int): JSONObject? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("{")) {
            return try {
                JSONObject(trimmed).takeIf { it.has("result") || it.has("error") }
            } catch (_: Exception) {
                null
            }
        }
        if (trimmed.startsWith("[")) {
            return try {
                val array = JSONArray(trimmed)
                var fallback: JSONObject? = null
                for (index in 0 until array.length()) {
                    val candidate = array.optJSONObject(index) ?: continue
                    if (!candidate.has("result") && !candidate.has("error")) continue
                    if (matchesId(candidate, expectedId)) return candidate
                    fallback = candidate
                }
                fallback
            } catch (_: Exception) {
                null
            }
        }

        // Streamable HTTP may return Server-Sent Events. SSE allows one JSON
        // value to span multiple `data:` lines, so accumulate each event and
        // prefer the message whose JSON-RPC id matches this request.
        var fallback: JSONObject? = null
        var match: JSONObject? = null
        val data = StringBuilder()

        fun acceptEvent() {
            if (data.isEmpty()) return
            val payload = data.toString().trim()
            data.setLength(0)
            if (payload.isEmpty() || (!payload.startsWith("{") && !payload.startsWith("["))) return
            try {
                if (payload.startsWith("[")) {
                    val array = JSONArray(payload)
                    for (index in 0 until array.length()) {
                        val candidate = array.optJSONObject(index) ?: continue
                        if (!candidate.has("result") && !candidate.has("error")) continue
                        if (matchesId(candidate, expectedId)) match = candidate
                        else fallback = candidate
                    }
                } else {
                    val candidate = JSONObject(payload)
                    if (candidate.has("result") || candidate.has("error")) {
                        if (matchesId(candidate, expectedId)) match = candidate
                        else fallback = candidate
                    }
                }
            } catch (_: Exception) {
                // Ignore heartbeat/non-JSON SSE data.
            }
        }

        trimmed.lineSequence().forEach { line ->
            if (line.isBlank()) {
                acceptEvent()
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }
        }
        acceptEvent()
        return match ?: fallback
    }

    private fun matchesId(candidate: JSONObject, expectedId: Int): Boolean {
        if (expectedId == Int.MIN_VALUE) return false
        return candidate.optInt("id", Int.MIN_VALUE) == expectedId
    }

    private fun extractText(result: JSONObject): String {
        val builder = StringBuilder()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "text" -> builder.append(item.optString("text"))
                    "resource" -> {
                        val resource = item.optJSONObject("resource")
                        if (resource != null) {
                            builder.append(resource.optString("text"))
                            if (resource.optString("text").isBlank()) {
                                builder.append(resource.optString("uri"))
                            }
                        }
                    }
                    else -> builder.append(item.toString())
                }
                if (builder.isNotEmpty()) builder.append('\n')
            }
        }
        if (builder.isBlank()) {
            val structured = result.opt("structuredContent")
            if (structured != null) builder.append(structured.toString())
        }
        return builder.toString().trim().take(MAX_OUTPUT_CHARS)
    }

    private data class RawResponse(
        val body: String,
        val sessionId: String?
    )

    private companion object {
        const val TAG = "McpHttpClient"

        /**
         * Newest first. The server answers with the version it supports and the
         * negotiated value is used for the rest of the session.
         */
        val CLIENT_PROTOCOL_VERSIONS = listOf("2025-06-18", "2024-11-05")
        const val PROTOCOL_VERSION = "2024-11-05"
        const val MAX_TOOL_PAGES = 5
        const val MAX_TOOLS = 100
        const val MAX_DESCRIPTION_CHARS = 1_000
        const val MAX_OUTPUT_CHARS = 20_000
        const val MAX_RESPONSE_CHARS = 2_000_000
        const val MAX_RESPONSE_BYTES = 2_000_000L
        val TOOL_NAME_REGEX = Regex("^[A-Za-z0-9_.-]{1,128}$")
    }
}
