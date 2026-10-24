package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.voice.VoiceInteractionService
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.voice.JarvisAgentBridge
import com.jarvis.assistant.voice.JarvisFloatingOrbController
import com.jarvis.assistant.voice.JarvisOrbView
import com.jarvis.assistant.voice.JarvisVoiceInteractionSession
import com.jarvis.assistant.voice.SherpaOnnxWakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Native VoiceInteractionService add-on.
 *
 * ============================ ZERO NOTIFICATION ============================
 * This class never calls startForeground(), never creates a NotificationChannel
 * and never posts a notification. The system binds it because the user selected
 * Jarvis as the assistant (or the KernelSU helper wrote the secure setting), so
 * no persistent "Jarvis is running" notification is required.
 *
 * ======================= EXISTING LOGIC IS UNTOUCHED ======================
 * The existing JarvisConversationService, JarvisAccessibilityService,
 * ShizukuManager, McpManager and command pipeline are not modified or
 * reimplemented. This service only:
 *   1. listens for the single keyword "jarvis" with Sherpa-ONNX KWS
 *   2. asks the system to show the transparent orb session
 *   3. hands control back to the original pipeline through JarvisAgentBridge
 *
 * It also stops the legacy foreground service *only while that service is in
 * standby*, so two wake listeners never fight over the microphone. The legacy
 * service is started again (with its original public action) after a wake.
 * Vosk remains only inside the existing speaker-biometrics model.
 * ==========================================================================
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "JarvisVoiceService"

        @Volatile
        private var activeInstance: JarvisVoiceInteractionService? = null

        /**
         * Called by the session when the orb hides. It is safe if the system
         * has already destroyed the session or the voice service.
         */
        fun onSessionHidden() {
            activeInstance?.handleSessionHidden()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeWordEngine: SherpaOnnxWakeWordEngine? = null
    private var ready = false
    private var screenOn = true
    private var sessionVisible = false
    private var receiversRegistered = false
    private var legacyStateJob: Job? = null
    private var legacyStandbyStopJob: Job? = null
    private var orbFallbackJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onReady() {
        super.onReady()
        if (ready) return
        ready = true

        screenOn = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        } catch (_: Throwable) {
            true
        }

        wakeWordEngine = SherpaOnnxWakeWordEngine(
            context = applicationContext,
            scope = serviceScope,
            onWakeWord = { requestOrb("wake-word") }
        )

        registerReceivers()
        observeLegacyServiceState()

        if (screenOn) {
            // Do not start a second listener while the legacy service is in
            // standby. resumeWakeWordIfPossible() stops that listener first.
            resumeWakeWordIfPossible()
        } else {
            Log.i(TAG, "Screen is off at onReady; keyword spotter starts on ACTION_SCREEN_ON")
        }
    }

    override fun onShutdown() {
        ready = false
        sessionVisible = false
        unregisterReceivers()
        wakeWordEngine?.stop()
        wakeWordEngine = null
        orbFallbackJob?.cancel()
        orbFallbackJob = null
        JarvisFloatingOrbController.hideNow()
        legacyStateJob?.cancel()
        legacyStateJob = null
        legacyStandbyStopJob?.cancel()
        legacyStandbyStopJob = null
        super.onShutdown()
    }

    override fun onDestroy() {
        ready = false
        sessionVisible = false
        unregisterReceivers()
        wakeWordEngine?.stop()
        wakeWordEngine = null
        orbFallbackJob?.cancel()
        orbFallbackJob = null
        JarvisFloatingOrbController.hideNow()
        legacyStateJob?.cancel()
        legacyStateJob = null
        legacyStandbyStopJob?.cancel()
        legacyStandbyStopJob = null
        serviceScope.cancel()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        requestOrb("keyguard-assist")
    }

    override fun onShowSessionFailed(args: Bundle) {
        super.onShowSessionFailed(args)
        Log.w(TAG, "System failed to show the voice session")
        sessionVisible = false
        orbFallbackJob?.cancel()
        orbFallbackJob = null
        JarvisFloatingOrbController.hide(animated = false)
        resumeWakeWordIfPossible()
    }

    private fun requestOrb(source: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestOrb(source) }
            return
        }
        if (!ready) {
            Log.w(TAG, "Ignoring $source trigger because the voice service is not ready")
            return
        }
        if (sessionVisible) {
            // An overlay can outlive a missed session callback. Re-assert it on
            // the main looper instead of dropping the wake event.
            JarvisFloatingOrbController.show(
                this,
                JarvisOrbView.Mode.LISTENING,
                onTap = { handleFloatingOrbTap() },
                onLongPress = { dismissFloatingOrb() }
            )
            return
        }

        sessionVisible = true
        // Release the keyword microphone before the existing conversation
        // pipeline opens its own audio recorder. The shared bus keeps the mic
        // alive and hands the pre-roll to AudioRecorder.
        wakeWordEngine?.pause()
        legacyStandbyStopJob?.cancel()
        legacyStandbyStopJob = null

        // Attach the orb immediately, before showSession(). This is the
        // reliable path: WindowManager.addView is executed on the main looper
        // and guarded by the controller even if the system session is slow or
        // silently fails.
        val overlayShown = JarvisFloatingOrbController.show(
            this,
            JarvisOrbView.Mode.LISTENING,
            onTap = { handleFloatingOrbTap() },
            onLongPress = { dismissFloatingOrb() }
        )

        try {
            Log.i(TAG, "Showing orb session from $source (overlay=$overlayShown)")
            showSession(Bundle(), 0)
        } catch (t: Throwable) {
            Log.e(TAG, "showSession failed: ${t.message}", t)
            // Keep sessionVisible=true while the overlay is still attached.
            // The fallback job will start the existing agent directly and then
            // hide the overlay/resume the wake engine if that also fails.
            JarvisAgentBridge.startExistingAgent(applicationContext)
            scheduleOrbFallback()
        }

        if (overlayShown) {
            scheduleOrbFallback()
        }
    }

    private fun handleFloatingOrbTap() {
        if (JarvisConversationService.isSessionOn.value) {
            JarvisConversationService.interruptPlayback()
        } else {
            JarvisAgentBridge.startExistingAgent(applicationContext)
        }
    }

    private fun dismissFloatingOrb() {
        orbFallbackJob?.cancel()
        orbFallbackJob = null
        if (!JarvisVoiceInteractionSession.dismissActiveSession()) {
            JarvisFloatingOrbController.hide(animated = true)
            handleSessionHidden()
        }
    }

    /**
     * If the session/agent never reaches an active conversation, do not leave
     * an orphaned overlay and do not leave the wake engine paused forever.
     */
    private fun scheduleOrbFallback() {
        orbFallbackJob?.cancel()
        orbFallbackJob = serviceScope.launch {
            delay(900L)
            if (!sessionVisible) return@launch
            if (!JarvisConversationService.isSessionOn.value &&
                !JarvisConversationService.isStandby.value
            ) {
                JarvisAgentBridge.startExistingAgent(applicationContext)
            }

            delay(10_000L)
            if (sessionVisible && !JarvisConversationService.isSessionOn.value) {
                Log.i(TAG, "Orb fallback grace period expired; hiding overlay")
                if (!JarvisVoiceInteractionSession.dismissActiveSession()) {
                    JarvisFloatingOrbController.hide(animated = true)
                    handleSessionHidden()
                }
            }
        }
    }

    private fun handleSessionHidden() {
        sessionVisible = false
        orbFallbackJob?.cancel()
        orbFallbackJob = null
        JarvisFloatingOrbController.hide(animated = false)
        resumeWakeWordIfPossible()
    }

    private fun resumeWakeWordIfPossible() {
        if (!ready || sessionVisible || !screenOn) return
        if (JarvisConversationService.isSessionOn.value) return
        if (JarvisConversationService.isStandby.value) {
            // The legacy service is still listening. Stop only that standby
            // listener, then start the add-on's keyword spotter so there is
            // exactly one microphone owner.
            stopLegacyStandbyThenResume()
            return
        }
        wakeWordEngine?.resume()
    }

    private fun observeLegacyServiceState() {
        legacyStateJob?.cancel()
        legacyStateJob = serviceScope.launch {
            combine(
                JarvisConversationService.isSessionOn,
                JarvisConversationService.isStandby
            ) { sessionOn, standby -> sessionOn to standby }
                .collect { (sessionOn, standby) ->
                    if (sessionOn) {
                        // The untouched Jarvis agent has the microphone.
                        wakeWordEngine?.pause()
                        legacyStandbyStopJob?.cancel()
                        legacyStandbyStopJob = null
                        return@collect
                    }

                    if (standby) {
                        stopLegacyStandbyThenResume()
                    } else {
                        resumeWakeWordIfPossible()
                    }
                }
        }
    }

    private fun stopLegacyStandbyThenResume() {
        if (!ready || sessionVisible) return
        if (JarvisConversationService.isSessionOn.value) return
        if (legacyStandbyStopJob?.isActive == true) return

        legacyStandbyStopJob = serviceScope.launch {
            Log.i(TAG, "Legacy Jarvis standby detected; stopping it to free the microphone")
            JarvisAgentBridge.stopLegacyStandby(applicationContext)
            delay(750L)
            legacyStandbyStopJob = null
            if (!sessionVisible && screenOn &&
                !JarvisConversationService.isSessionOn.value &&
                !JarvisConversationService.isStandby.value
            ) {
                wakeWordEngine?.resume()
            }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            screenFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val assistFilter = IntentFilter().apply {
            addAction(Intent.ACTION_ASSIST)
            addAction(Intent.ACTION_VOICE_COMMAND)
        }
        ContextCompat.registerReceiver(
            this,
            assistReceiver,
            assistFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        try {
            unregisterReceiver(assistReceiver)
        } catch (_: Throwable) {
        }
        receiversRegistered = false
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    wakeWordEngine?.pause()
                    // If the legacy service is only in standby, stop that
                    // listener too. This closes the microphone on screen off
                    // instead of leaving a second Sherpa spotter running.
                    if (JarvisConversationService.isStandby.value &&
                        !JarvisConversationService.isSessionOn.value
                    ) {
                        legacyStandbyStopJob?.cancel()
                        legacyStandbyStopJob = null
                        JarvisAgentBridge.stopLegacyStandby(applicationContext)
                    }
                    Log.i(TAG, "Screen off: keyword spotter paused")
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    if (!sessionVisible && !JarvisConversationService.isSessionOn.value) {
                        if (JarvisConversationService.isStandby.value) {
                            stopLegacyStandbyThenResume()
                        } else {
                            wakeWordEngine?.resume()
                        }
                    }
                    Log.i(TAG, "Screen on: keyword spotter resumed")
                }
            }
        }
    }

    /**
     * Best-effort routing for OEM power-button/assist gestures. On most devices
     * a normal app cannot read the raw power key; the system instead sends an
     * assist request to the selected assistant. This receiver and
     * onLaunchVoiceAssistFromKeyguard() cover that system-owned path without
     * requesting any privileged input permission.
     */
    private val assistReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_ASSIST,
                Intent.ACTION_VOICE_COMMAND -> requestOrb("assist-gesture")
            }
        }
    }
}
