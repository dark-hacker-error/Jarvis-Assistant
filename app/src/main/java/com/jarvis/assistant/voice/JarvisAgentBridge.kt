package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.background.JarvisConversationService

/**
 * Thin bridge between the new wake-word/session front-end and the *existing*
 * Jarvis agent.
 *
 * IMPORTANT:
 * There is deliberately no command parsing, no accessibility implementation,
 * no Shizuku call and no MCP code in this file. The existing conversation
 * service remains the single owner of those systems. The only integration is
 * the public ACTION_START_CONVERSATION broadcast/action that the app already
 * uses.
 */
object JarvisAgentBridge {

    private const val TAG = "JarvisAgentBridge"

    /**
     * ===================== EXISTING AGENT PLUG-IN POINT =====================
     *
     * This is the exact place where the new wake-word/orb module plugs into the
     * untouched Jarvis command pipeline. After the orb has animated in, the
     * original JarvisConversationService is started with its existing public
     * action. That service keeps ownership of:
     *
     *   - Gemini Live audio and tool calls
     *   - JarvisAccessibilityService multi-step actions and wait/click logic
     *   - ShizukuManager privileged calls
     *   - McpManager tool execution
     *   - the original command/audio pipeline and its owner-voice checks
     *
     * The add-on does not duplicate or rewrite any of the above. It only brings
     * the original pipeline back to the foreground.
     * =======================================================================
     */
    fun startExistingAgent(context: Context) {
        if (JarvisConversationService.isSessionOn.value) return

        val intent = Intent(context, JarvisConversationService::class.java).apply {
            action = JarvisConversationService.ACTION_START_CONVERSATION
        }

        try {
            ContextCompat.startForegroundService(context, intent)
            Log.i(TAG, "Existing Jarvis agent conversation start requested")
        } catch (t: Throwable) {
            // Some OEMs refuse a foreground-service start from a background
            // assistant. Try a normal start as a last resort; the existing
            // service still enforces its own permissions and lifecycle.
            Log.w(TAG, "Foreground start refused, retrying normal service start: ${t.message}")
            try {
                context.startService(intent)
            } catch (fallback: Throwable) {
                Log.e(TAG, "Unable to start existing Jarvis agent: ${fallback.message}", fallback)
            }
        }
    }

    /**
     * Stops the legacy standby listener so only ONE wake microphone owner is
     * active. This uses the existing service lifecycle only; it does not change
     * any of its code or command behavior.
     *
     * The coordinator calls this only when the legacy service is in standby
     * (not during an active conversation).
     */
    fun stopLegacyStandby(context: Context) {
        try {
            val stopped = context.stopService(
                Intent(context, JarvisConversationService::class.java)
            )
            Log.i(TAG, "Legacy standby stop requested (stopped=$stopped)")
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to stop legacy standby: ${t.message}")
        }
    }
}
