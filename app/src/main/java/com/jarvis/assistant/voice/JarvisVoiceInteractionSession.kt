package com.jarvis.assistant.voice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.jarvis.assistant.JarvisVoiceInteractionService
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.background.JarvisConversationService

/**
 * Transparent system session that draws the Siri/Gemini style orb.
 *
 * Design contract:
 *  - The session window is full-screen transparent but NOT_TOUCHABLE.
 *  - A separate tiny overlay owns only the orb circle / working pill touches.
 *    Everything outside that circle passes through to the app underneath.
 *  - The root is IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, so the
 *    existing accessibility agent never reads its own orb.
 *  - No notification is created anywhere in this class.
 */
class JarvisVoiceInteractionSession(
    private val appContext: Context
) : VoiceInteractionSession(appContext) {

    companion object {
        private const val TAG = "JarvisVoiceSession"
        private const val WORKING_TEXT = "Jarvis is working..."
        private val TERMINAL_TASK_STATES = setOf(
            AgentTaskEngine.State.COMPLETED,
            AgentTaskEngine.State.FAILED,
            AgentTaskEngine.State.CANCELLED
        )

        @Volatile
        private var currentSession: JarvisVoiceInteractionSession? = null

        /**
         * Ask the live session to hide. Returns false when no session has been
         * shown yet, in which case the caller owns the standalone overlay.
         */
        fun dismissActiveSession(): Boolean {
            val session = currentSession ?: return false
            session.dismissFromService()
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: FrameLayout? = null
    private var orbView: JarvisOrbView? = null
    private var orbLayoutParams: FrameLayout.LayoutParams? = null
    private var touchOverlay: JarvisOrbTouchOverlay? = null
    private var usingFloatingOverlay = false

    private var stateJob: Job? = null
    private var exitJob: Job? = null
    private var fallbackJob: Job? = null

    private var shownAt = 0L
    private var seenActiveConversation = false
    private var agentStartRequested = false
    private var exiting = false

    override fun onCreateContentView(): View? {
        val context = appContext
        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        val orb = JarvisOrbView(context).apply {
            alpha = 0f
            translationY = JarvisOrbMetrics.dp(context, 56f).toFloat()
        }
        val layoutParams = FrameLayout.LayoutParams(
            JarvisOrbMetrics.dp(context, 140f),
            JarvisOrbMetrics.dp(context, 76f),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = JarvisOrbMetrics.bottomOffsetPx(context)
        }

        frame.addView(orb, layoutParams)

        frame.setOnApplyWindowInsetsListener { _, insets ->
            val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            layoutParams.bottomMargin = JarvisOrbMetrics.bottomOffsetPx(context, navBottom)
            orb.layoutParams = layoutParams
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets
            } else {
                insets
            }
        }

        root = frame
        orbView = orb
        orbLayoutParams = layoutParams
        return frame
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        setKeepAwake(false)
        setUiEnabled(true)
        applyTransparentPassThroughWindow()

        shownAt = SystemClock.elapsedRealtime()
        seenActiveConversation = false
        agentStartRequested = false
        exiting = false
        currentSession = this

        // Prefer the main-thread WindowManager overlay. It is already attached
        // by the wake callback on the fast path; this call is idempotent and
        // only updates the session callbacks. If overlay permission is missing,
        // fall back to the visual orb inside the system session window.
        usingFloatingOverlay = JarvisFloatingOrbController.show(
            context = appContext,
            mode = JarvisOrbView.Mode.LISTENING,
            onTap = { handleOrbTap() },
            onLongPress = { hideWithAnimation() }
        )

        val orb = orbView
        touchOverlay?.remove()
        touchOverlay = null

        if (usingFloatingOverlay) {
            orb?.animate()?.cancel()
            orb?.alpha = 0f
        } else if (orb != null) {
            orb.alpha = 0f
            orb.translationY = JarvisOrbMetrics.dp(appContext, 56f).toFloat()
            orb.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.12f))
                .start()

            touchOverlay = JarvisOrbTouchOverlay(
                context = appContext,
                onTap = { handleOrbTap() },
                onLongPress = { hideWithAnimation() }
            ).also { it.show(JarvisOrbView.Mode.LISTENING) }
        }

        observeConversationAndAgent()

        // Give the entry animation a beat before the existing agent pipeline is
        // asked to start. The bridge only requests the untouched service; no
        // command/agent logic lives in this add-on.
        root?.postDelayed({
            if (!exiting && !seenActiveConversation) {
                agentStartRequested = true
                JarvisAgentBridge.startExistingAgent(appContext)
            }
        }, 280L)

        // If the existing agent refuses to start (for example, API key missing),
        // never leave an invisible touch target behind forever.
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            delay(10_000L)
            if (!seenActiveConversation && !hasRunningTask() && !exiting) {
                Log.i(TAG, "No active conversation after grace period; hiding orb")
                hideWithAnimation()
            }
        }
    }

    override fun onHide() {
        cleanup()
        JarvisVoiceInteractionService.onSessionHidden()
        super.onHide()
    }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        JarvisVoiceInteractionService.onSessionHidden()
        super.onDestroy()
    }

    override fun onBackPressed() {
        hideWithAnimation()
    }

    private fun applyTransparentPassThroughWindow() {
        val dialog = window ?: return
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun observeConversationAndAgent() {
        stateJob?.cancel()
        stateJob = scope.launch {
            combine(
                JarvisConversationService.isSessionOn,
                JarvisConversationService.isStandby,
                JarvisConversationService.audioLevel,
                JarvisConversationService.conversationState,
                AgentTaskEngine.taskState
            ) { sessionOn, standby, audioLevel, _, _ ->
                RuntimeState(sessionOn = sessionOn, standby = standby, audioLevel = audioLevel)
            }.collect { state ->
                if (exiting) return@collect

                val working = hasRunningTask()
                if (working) {
                    applyVisualState(
                        mode = JarvisOrbView.Mode.WORKING,
                        status = WORKING_TEXT,
                        amplitude = null
                    )
                } else {
                    applyVisualState(
                        mode = JarvisOrbView.Mode.LISTENING,
                        status = null,
                        amplitude = state.audioLevel
                    )
                }

                if (state.sessionOn) {
                    seenActiveConversation = true
                    cancelExit()
                } else if (seenActiveConversation) {
                    scheduleExit(if (state.standby) 450L else 1_250L)
                } else if (agentStartRequested &&
                    SystemClock.elapsedRealtime() - shownAt > 4_000L &&
                    !working
                ) {
                    scheduleExit(1_000L)
                }
            }
        }
    }

    private fun hasRunningTask(): Boolean {
        val task = AgentTaskEngine.current() ?: return false
        return task.status !in TERMINAL_TASK_STATES
    }

    private fun scheduleExit(delayMs: Long) {
        if (exiting) return
        exitJob?.cancel()
        exitJob = scope.launch {
            delay(delayMs)
            if (!JarvisConversationService.isSessionOn.value && !hasRunningTask()) {
                hideWithAnimation()
            }
        }
    }

    private fun cancelExit() {
        exitJob?.cancel()
        exitJob = null
        fallbackJob?.cancel()
        fallbackJob = null
    }

    private fun handleOrbTap() {
        if (JarvisConversationService.isSessionOn.value) {
            // Existing public behavior only; no private agent code is touched.
            JarvisConversationService.interruptPlayback()
        } else {
            agentStartRequested = true
            JarvisAgentBridge.startExistingAgent(appContext)
        }
    }

    private fun applyVisualState(
        mode: JarvisOrbView.Mode,
        status: String?,
        amplitude: Float?
    ) {
        if (usingFloatingOverlay) {
            JarvisFloatingOrbController.update(mode, status, amplitude)
            return
        }
        orbView?.setMode(mode, status)
        if (amplitude != null) orbView?.setAmplitude(amplitude)
        touchOverlay?.update(mode)
    }

    private fun dismissFromService() {
        if (exiting) return
        val view = root
        if (view == null) {
            hideWithAnimation()
        } else {
            view.post { hideWithAnimation() }
        }
    }

    private fun hideWithAnimation() {
        if (exiting) return
        exiting = true
        exitJob?.cancel()
        fallbackJob?.cancel()
        touchOverlay?.remove()
        touchOverlay = null

        if (usingFloatingOverlay) {
            JarvisFloatingOrbController.hide(animated = true) { finishSafely() }
            return
        }

        val orb = orbView
        if (orb == null) {
            finishSafely()
            return
        }

        orb.animate().cancel()
        orb.animate()
            .alpha(0f)
            .translationY(JarvisOrbMetrics.dp(appContext, 56f).toFloat())
            .setDuration(230L)
            .withEndAction { finishSafely() }
            .start()
    }

    private fun finishSafely() {
        try {
            finish()
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to finish voice session: ${t.message}")
        }
    }

    private fun cleanup() {
        exiting = true
        stateJob?.cancel()
        stateJob = null
        exitJob?.cancel()
        exitJob = null
        fallbackJob?.cancel()
        fallbackJob = null
        touchOverlay?.remove()
        touchOverlay = null
        // The session owns the overlay for the duration of its lifetime. If the
        // system hides the session directly, remove the overlay as well.
        JarvisFloatingOrbController.hideNow()
        usingFloatingOverlay = false
        if (currentSession === this) currentSession = null
    }

    private data class RuntimeState(
        val sessionOn: Boolean,
        val standby: Boolean,
        val audioLevel: Float
    )
}
