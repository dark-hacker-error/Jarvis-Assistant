package com.jarvis.assistant.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Bitmap
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JarvisAccessibilityService : AccessibilityService() {

    data class UiChange(val revision: Long, val packageName: String, val eventType: Int, val timestamp: Long)

    companion object {
        private const val TAG = "JarvisAccessibility"
        private val _uiChanges = MutableStateFlow(UiChange(0L, "", 0, 0L))
        val uiChanges: StateFlow<UiChange> = _uiChanges.asStateFlow()

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
        fun isEnabled(): Boolean = instance != null

        fun triggerWhatsAppSend(targetText: String? = null) {
            instance?.armWhatsAppSend(targetText)
        }

        fun triggerWhatsAppCall(isVideo: Boolean) {
            instance?.armWhatsAppCall(isVideo)
        }

        fun triggerYouTubeAutoPlay() {
            instance?.armYouTubePlay()
        }

        fun triggerYouTubeSubscribe() {
            instance?.armYouTubeSubscribe()
        }

        fun triggerYouTubeFullscreen() {
            instance?.armYouTubeFullscreen()
        }

        fun triggerWhatsAppDelete(forEveryone: Boolean) {
            instance?.armWhatsAppDelete(forEveryone)
        }

        data class ExtractedOtp(val code: String, val timestamp: Long, val senderPkg: String)
        @Volatile
        var latestExtractedOtp: ExtractedOtp? = null

        fun getRecentOtp(): String? {
            val otp = latestExtractedOtp ?: return null
            if (System.currentTimeMillis() - otp.timestamp < 120_000L) { // valid for 2 minutes
                return otp.code
            }
            return null
        }

        suspend fun captureScreenBitmap(): Bitmap? {
            val s = instance ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return try {
                withTimeoutOrNull(1500L) {
                    suspendCancellableCoroutine { cont ->
                        s.takeScreenCapture { bmp ->
                            if (cont.isActive) {
                                cont.resume(bmp, null)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "captureScreenBitmap timed out or failed: ${e.message}")
                null
            }
        }

        suspend fun captureScreenJpeg(quality: Int = 75): ByteArray? {
            // FAST PATH: If ScreenCaptureManager VirtualDisplay is running, grab immediate hardware frame (0ms latency)
            val scm = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
            if (scm.isScreenShareActive()) {
                val fastSnap = scm.captureSnapshotJpeg(quality)
                if (fastSnap != null) return fastSnap
            }

            val bmp = captureScreenBitmap()
            if (bmp != null) {
                try {
                    val maxDim = 640f
                    val scale = (maxDim / maxOf(bmp.width, bmp.height)).coerceAtMost(1.0f)
                    val targetW = (bmp.width * scale).toInt() and 1.inv()
                    val targetH = (bmp.height * scale).toInt() and 1.inv()
                    val scaledBmp = if (scale < 1.0f && targetW > 0 && targetH > 0) {
                        Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                    } else {
                        bmp
                    }
                    val stream = ByteArrayOutputStream()
                    scaledBmp.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    if (scaledBmp != bmp) scaledBmp.recycle()
                    bmp.recycle()
                    val bytes = stream.toByteArray()
                    Log.d(TAG, "Captured native accessibility screenshot: ${bytes.size / 1024} KB (${targetW}x${targetH})")
                    return bytes
                } catch (e: Exception) {
                    Log.e(TAG, "Failed encoding accessibility screenshot to JPEG: ${e.message}")
                }
            }
            // Fallback to ScreenCaptureManager if active
            return com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().captureSnapshotJpeg(quality)
        }
    }

    /**
     * Native Android 11+ (API 30+) AccessibilityService screen capture.
     * Takes a hardware buffer screenshot with ZERO user prompts or system dialogs.
     */
    fun takeScreenCapture(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    Executors.newSingleThreadExecutor(),
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                            try {
                                val buffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                buffer.close()
                                hwBitmap?.recycle()
                                callback(softBitmap)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error wrapping hardware buffer screenshot: ${e.message}")
                                callback(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed with errorCode: $errorCode")
                            callback(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception: ${e.message}")
                callback(null)
            }
        } else {
            callback(null)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var isWaitingForWhatsAppSend = false
    private var pendingWhatsAppMessage: String? = null
    private var whatsAppArmTime = 0L
    private var whatsAppSendTaps = 0

    private var isWaitingForWhatsAppCall = false
    private var isVideoCall = false
    private var whatsAppCallArmTime = 0L
    private var whatsAppCallTaps = 0

    private var isWaitingForYouTubePlay = false
    private var youTubeArmTime = 0L

    private var isWaitingForYouTubeSubscribe = false
    private var youTubeSubscribeArmTime = 0L

    private var isWaitingForYouTubeFullscreen = false
    private var youTubeFullscreenArmTime = 0L

    private var isWaitingForWhatsAppDelete = false
    private var deleteForEveryone = false
    private var whatsAppDeleteArmTime = 0L
    private var deleteStage = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JarvisAccessibilityService connected and active")
        ensureJarvisServiceActive()
    }

    private fun ensureJarvisServiceActive() {
        try {
            if (!com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value &&
                !com.jarvis.assistant.background.JarvisConversationService.isStandby.value) {
                val intent = Intent(this, com.jarvis.assistant.background.JarvisConversationService::class.java).apply {
                    action = com.jarvis.assistant.background.JarvisConversationService.ACTION_ENTER_STANDBY
                }
                androidx.core.content.ContextCompat.startForegroundService(this, intent)
                Log.i(TAG, "JarvisAccessibilityService ensured JarvisConversationService is running in standby")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start JarvisConversationService from accessibility service: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString().orEmpty()
        if (event.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            )) {
            val previous = _uiChanges.value
            _uiChanges.value = UiChange(previous.revision + 1, pkg, event.eventType, System.currentTimeMillis())
        }

        // Auto-extract incoming OTP / verification codes from notifications (SMS, WhatsApp, banking)
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            try {
                val texts = event.text
                val fullText = texts.joinToString(" ")
                if (fullText.isNotBlank()) {
                    val otpRegex = Regex("""\b(?:OTP|code|verification|passcode|is|pin|password)[^\d]*(\d{4,8})\b""", RegexOption.IGNORE_CASE)
                    val match = otpRegex.find(fullText) ?: Regex("""\b(\d{4,6})\b""").find(fullText)
                    if (match != null) {
                        val code = match.groupValues.lastOrNull { it.isNotBlank() && it.all { c -> c.isDigit() } }
                        if (code != null && code.length in 4..8) {
                            Log.i(TAG, "Auto-detected incoming OTP/code from notification ($pkg): $code")
                            latestExtractedOtp = ExtractedOtp(code, System.currentTimeMillis(), pkg)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error inspecting notification for OTP: ${e.message}")
            }
        }

        if (isWaitingForWhatsAppSend && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")) {
            if (System.currentTimeMillis() - whatsAppArmTime < 14000) {
                attemptWhatsAppSendAction(0)
            } else {
                isWaitingForWhatsAppSend = false
            }
        }

        if (isWaitingForWhatsAppCall && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")) {
            if (System.currentTimeMillis() - whatsAppCallArmTime < 14000) {
                attemptWhatsAppCallAction(0)
            } else {
                isWaitingForWhatsAppCall = false
            }
        }

        if (isWaitingForYouTubePlay && (pkg == "com.google.android.youtube" || pkg == "app.revanced.android.youtube")) {
            if (System.currentTimeMillis() - youTubeArmTime < 10000) {
                attemptClickFirstYouTubeVideo(0)
            } else {
                isWaitingForYouTubePlay = false
            }
        }

        if (isWaitingForYouTubeSubscribe && (pkg == "com.google.android.youtube" || pkg == "app.revanced.android.youtube")) {
            if (System.currentTimeMillis() - youTubeSubscribeArmTime < 10000) {
                attemptYouTubeSubscribe()
            } else {
                isWaitingForYouTubeSubscribe = false
            }
        }

        if (isWaitingForYouTubeFullscreen && (pkg == "com.google.android.youtube" || pkg == "app.revanced.android.youtube")) {
            if (System.currentTimeMillis() - youTubeFullscreenArmTime < 8000) {
                attemptYouTubeFullscreen(1)
            } else {
                isWaitingForYouTubeFullscreen = false
            }
        }

        if (isWaitingForWhatsAppDelete && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")) {
            if (System.currentTimeMillis() - whatsAppDeleteArmTime < 15000) {
                attemptWhatsAppDeleteAction()
            } else {
                isWaitingForWhatsAppDelete = false
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "JarvisAccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // -------------------------------------------------------------
    // WhatsApp Send & Text Injection
    // -------------------------------------------------------------

    fun armWhatsAppSend(targetText: String?) {
        isWaitingForWhatsAppSend = true
        pendingWhatsAppMessage = targetText
        whatsAppArmTime = System.currentTimeMillis()
        whatsAppSendTaps = 0
        Log.i(TAG, "Armed for WhatsApp auto-send (has text=${!targetText.isNullOrBlank()})")

        // Rapid polling to catch WhatsApp UI as soon as it renders
        handler.postDelayed({ pollWhatsAppSend(1) }, 600)
    }

    private fun pollWhatsAppSend(attempt: Int) {
        if (!isWaitingForWhatsAppSend || attempt > 12) {
            isWaitingForWhatsAppSend = false
            pendingWhatsAppMessage = null
            return
        }

        val success = attemptWhatsAppSendAction(attempt)
        if (success && whatsAppSendTaps >= 1) {
            // Check if message sent and entry is cleared
            val root = getActiveRootNode()
            val entryText = root?.let { getWhatsAppEntryText(it) }
            if (entryText.isNullOrBlank()) {
                Log.i(TAG, "WhatsApp message sent! Entry field cleared. Ending auto-send polling.")
                isWaitingForWhatsAppSend = false
                pendingWhatsAppMessage = null
                return
            }
        }

        val delay = if (attempt < 3) 400L else 600L
        handler.postDelayed({ pollWhatsAppSend(attempt + 1) }, delay)
    }

    private fun attemptWhatsAppSendAction(attempt: Int): Boolean {
        val rootNode = getActiveRootNode() ?: return false

        val currentEntryText = getWhatsAppEntryText(rootNode)

        // If we already tapped send once:
        if (whatsAppSendTaps >= 1) {
            if (currentEntryText.isNullOrBlank()) {
                Log.i(TAG, "WhatsApp message sent successfully (entry is empty). Disarming.")
                isWaitingForWhatsAppSend = false
                pendingWhatsAppMessage = null
                return true
            }
            // If entry is not empty yet, wait for WhatsApp to process. DO NOT tap mic.
            Log.d(TAG, "Send already tapped once, waiting for entry to clear (attempt $attempt)")
            return false
        }

        // 1. Text injection check: if text field is empty and we have a message, inject it
        if (currentEntryText.isNullOrBlank()) {
            injectTextIfEmpty(rootNode)
        }

        // Ensure entry actually has text before attempting to click send!
        // When entry is empty, the button is guaranteed to be a microphone/voice note button.
        val textAfterInject = getWhatsAppEntryText(rootNode)
        if (textAfterInject.isNullOrBlank()) {
            Log.d(TAG, "Entry field is empty; skipping send click to prevent clicking mic (attempt $attempt)")
            return false
        }

        // 2. Find and click ONLY the actual Send button (never send_container which holds the mic)
        var clicked = false
        val sendButtonIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/send_btn",
            "com.whatsapp:id/send_media_button",
            "com.whatsapp.w4b:id/send_btn"
        )
        for (id in sendButtonIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (isVoiceOrMicNode(node)) {
                    Log.d(TAG, "Ignoring voice/mic node with ID $id")
                    continue
                }
                if (clickNodeAndTap(node)) {
                    Log.i(TAG, "Successfully clicked WhatsApp send button by ID: $id (attempt $attempt)")
                    clicked = true
                    whatsAppSendTaps++
                    break
                }
            }
            if (clicked) break
        }

        if (!clicked) {
            clicked = findAndClickSendInTree(rootNode)
            if (clicked) whatsAppSendTaps++
        }

        if (clicked) {
            // Schedule an immediate verification check
            handler.postDelayed({
                val checkRoot = getActiveRootNode()
                if (checkRoot != null && getWhatsAppEntryText(checkRoot).isNullOrBlank()) {
                    Log.i(TAG, "Verified: WhatsApp entry field is clear. Disarming send.")
                    isWaitingForWhatsAppSend = false
                    pendingWhatsAppMessage = null
                }
            }, 300)
        }

        return clicked
    }

    private fun isVoiceOrMicNode(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        if (desc.contains("voice") || desc.contains("record") || desc.contains("audio") ||
            desc.contains("mic") || desc.contains("microphone") || desc.contains("ptt") ||
            desc.contains("hold to") || desc.contains("ध्वनि") || desc.contains("बोलकर")) {
            return true
        }
        if (viewId.contains("voice") || viewId.contains("record") || viewId.contains("mic") || viewId.contains("ptt")) {
            return true
        }
        // Inspect child elements
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val cDesc = child.contentDescription?.toString()?.lowercase() ?: ""
            val cId = child.viewIdResourceName?.lowercase() ?: ""
            if (cDesc.contains("voice") || cDesc.contains("record") || cDesc.contains("audio") ||
                cDesc.contains("mic") || cDesc.contains("ptt") || cId.contains("mic") || cId.contains("voice")) {
                return true
            }
        }
        return false
    }

    private fun getWhatsAppEntryText(root: AccessibilityNodeInfo): String? {
        val entryIds = listOf("com.whatsapp:id/entry", "com.whatsapp.w4b:id/entry")
        for (id in entryIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                val text = node.text?.toString()
                if (text != null) return text.trim()
            }
        }
        // Fallback: search EditText
        return findFirstEditTextContent(root)
    }

    private fun findFirstEditTextContent(node: AccessibilityNodeInfo): String? {
        if (node.className?.toString()?.contains("EditText") == true) {
            return node.text?.toString()?.trim()
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditTextContent(child)
            if (result != null) return result
        }
        return null
    }

    private fun injectTextIfEmpty(root: AccessibilityNodeInfo) {
        val targetText = pendingWhatsAppMessage
        if (targetText.isNullOrBlank()) return

        val entryIds = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry"
        )
        for (id in entryIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                val currentText = node.text?.toString()?.trim() ?: ""
                if (currentText.isBlank()) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetText)
                    }
                    val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Log.i(TAG, "Injected text into WhatsApp entry field: success=$set")
                    return
                }
            }
        }

        // Fallback: check all EditTexts in the active tree
        findAndInjectEditText(root, targetText)
    }

    private fun findAndInjectEditText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.className?.toString()?.contains("EditText") == true) {
            val currentText = node.text?.toString()?.trim() ?: ""
            if (currentText.isBlank()) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.i(TAG, "Injected text into generic EditText: success=$set")
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndInjectEditText(child, text)) return true
        }
        return false
    }

    private fun findAndClickSendInTree(node: AccessibilityNodeInfo): Boolean {
        if (isVoiceOrMicNode(node)) return false

        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // If content description is send
        if (contentDesc == "send" || contentDesc == "send message" || contentDesc == "enviar" || contentDesc == "भेजें") {
            if (clickNodeAndTap(node)) return true
        }

        // Strictly match send button IDs (never containers)
        if (viewId.endsWith("/send") || viewId.endsWith("/send_btn") || viewId.endsWith("/send_button") || viewId.endsWith("/send_media_button")) {
            if (clickNodeAndTap(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickSendInTree(child)) return true
        }

        return false
    }

    // -------------------------------------------------------------
    // WhatsApp Voice & Video Calling
    // -------------------------------------------------------------

    fun armWhatsAppCall(video: Boolean) {
        isWaitingForWhatsAppCall = true
        isVideoCall = video
        whatsAppCallArmTime = System.currentTimeMillis()
        whatsAppCallTaps = 0
        Log.i(TAG, "Armed for WhatsApp auto-call (video=$video)")
        handler.postDelayed({ pollWhatsAppCall(1) }, 700)
    }

    private fun pollWhatsAppCall(attempt: Int) {
        if (!isWaitingForWhatsAppCall || attempt > 16) {
            isWaitingForWhatsAppCall = false
            return
        }

        val clicked = attemptWhatsAppCallAction(attempt)
        if (clicked && whatsAppCallTaps >= 2) {
            isWaitingForWhatsAppCall = false
            return
        }

        val delay = if (attempt < 4) 400L else 700L
        handler.postDelayed({ pollWhatsAppCall(attempt + 1) }, delay)
    }

    private fun attemptWhatsAppCallAction(attempt: Int): Boolean {
        val rootNode = getActiveRootNode() ?: return false

        var clicked = false

        // 1. Check for system/app confirmation dialog ("Start voice call?", "Call", etc.)
        val dialogButtons = listOf("android:id/button1", "com.whatsapp:id/ok", "com.whatsapp:id/call_button")
        for (btnId in dialogButtons) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(btnId)
            for (node in nodes) {
                if (clickNodeAndTap(node)) {
                    Log.i(TAG, "Clicked call confirmation dialog button: $btnId")
                    whatsAppCallTaps++
                    clicked = true
                    break
                }
            }
            if (clicked) break
        }

        // Also check confirmation dialog text
        if (!clicked) {
            val callConfirmNodes = rootNode.findAccessibilityNodeInfosByText("Call")
            for (node in callConfirmNodes) {
                if (node.isClickable || node.parent?.isClickable == true) {
                    if (clickNodeAndTap(node)) {
                        Log.i(TAG, "Clicked confirmation dialog button by text 'Call'")
                        whatsAppCallTaps++
                        clicked = true
                        break
                    }
                }
            }
        }

        // 2. Click Top Action Bar Call Button (Video or Voice)
        val targetIds = if (isVideoCall) {
            listOf(
                "com.whatsapp:id/video_call",
                "com.whatsapp.w4b:id/video_call",
                "com.whatsapp:id/menuitem_conversations_call_video"
            )
        } else {
            listOf(
                "com.whatsapp:id/voice_call",
                "com.whatsapp.w4b:id/voice_call",
                "com.whatsapp:id/menuitem_conversations_call_voice"
            )
        }

        for (id in targetIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (clickNodeAndTap(node)) {
                    Log.i(TAG, "Clicked WhatsApp call button by ID: $id (video=$isVideoCall)")
                    whatsAppCallTaps++
                    clicked = true
                    break
                }
            }
            if (clicked) break
        }

        // Fallback by content description in tree
        if (!clicked) {
            clicked = findAndClickCallButtonInTree(rootNode, isVideoCall)
            if (clicked) whatsAppCallTaps++
        }

        return clicked
    }

    private fun findAndClickCallButtonInTree(node: AccessibilityNodeInfo, isVideo: Boolean): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val targetPhrase = if (isVideo) "video call" else "voice call"

        if (desc.contains(targetPhrase) || (!isVideo && (desc == "call" || desc.contains("audio call")))) {
            if (clickNodeAndTap(node)) {
                Log.i(TAG, "Clicked call button by content description: $desc")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickCallButtonInTree(child, isVideo)) return true
        }

        return false
    }

    // -------------------------------------------------------------
    // YouTube Auto Play
    // -------------------------------------------------------------

    fun armYouTubePlay() {
        isWaitingForYouTubePlay = true
        youTubeArmTime = System.currentTimeMillis()
        Log.i(TAG, "Armed for YouTube auto-play")
        handler.postDelayed({ pollYouTubePlay(1) }, 1000)
    }

    private fun pollYouTubePlay(attempt: Int) {
        if (!isWaitingForYouTubePlay || attempt > 10) {
            isWaitingForYouTubePlay = false
            return
        }
        val clicked = attemptClickFirstYouTubeVideo(attempt)
        if (!clicked) {
            handler.postDelayed({ pollYouTubePlay(attempt + 1) }, 600)
        }
    }

    private fun attemptClickFirstYouTubeVideo(attempt: Int): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val videoViewIds = listOf(
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/video_info",
            "com.google.android.youtube:id/title",
            "com.google.android.youtube:id/compact_renderer",
            "app.revanced.android.youtube:id/thumbnail",
            "app.revanced.android.youtube:id/title"
        )

        for (id in videoViewIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (clickNodeAndTap(node)) {
                    Log.i(TAG, "Clicked first YouTube video item by ID: $id (attempt $attempt)")
                    isWaitingForYouTubePlay = false
                    return true
                }
            }
        }

        return false
    }

    // -------------------------------------------------------------
    // YouTube Subscribe
    // -------------------------------------------------------------

    fun armYouTubeSubscribe() {
        isWaitingForYouTubeSubscribe = true
        youTubeSubscribeArmTime = System.currentTimeMillis()
        Log.i(TAG, "Armed for YouTube subscribe")
        handler.postDelayed({ pollYouTubeSubscribe(1) }, 500)
    }

    private fun pollYouTubeSubscribe(attempt: Int) {
        if (!isWaitingForYouTubeSubscribe || attempt > 12) {
            isWaitingForYouTubeSubscribe = false
            return
        }
        if (!attemptYouTubeSubscribe()) {
            handler.postDelayed({ pollYouTubeSubscribe(attempt + 1) }, 500)
        }
    }

    private fun attemptYouTubeSubscribe(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val subIds = listOf(
            "com.google.android.youtube:id/subscribe_button",
            "app.revanced.android.youtube:id/subscribe_button"
        )
        for (id in subIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                val text = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                if (text.contains("subscribed") || desc.contains("subscribed")) {
                    Log.i(TAG, "Already subscribed to this channel; nothing to do.")
                    isWaitingForYouTubeSubscribe = false
                    return true
                }
                if (clickNodeAndTap(node)) {
                    Log.i(TAG, "Clicked YouTube subscribe button by ID: $id")
                    isWaitingForYouTubeSubscribe = false
                    return true
                }
            }
        }

        // Fallback: find any clickable node whose text/description says "Subscribe"
        // (but never one that already reads "Subscribed", to avoid accidentally unsubscribing).
        val fallback = findSubscribeNodeInTree(rootNode)
        if (fallback != null && clickNodeAndTap(fallback)) {
            Log.i(TAG, "Clicked YouTube subscribe button via tree fallback")
            isWaitingForYouTubeSubscribe = false
            return true
        }

        return false
    }

    private fun findSubscribeNodeInTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val looksSubscribed = text.contains("subscribed") || desc.contains("subscribed")
        val looksLikeSubscribeButton = (text == "subscribe" || desc == "subscribe") && !looksSubscribed
        if (looksLikeSubscribeButton && (node.isClickable || node.parent?.isClickable == true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSubscribeNodeInTree(child)
            if (result != null) return result
        }
        return null
    }

    // -------------------------------------------------------------
    // YouTube Fullscreen Toggle
    // -------------------------------------------------------------

    fun armYouTubeFullscreen() {
        isWaitingForYouTubeFullscreen = true
        youTubeFullscreenArmTime = System.currentTimeMillis()
        Log.i(TAG, "Armed for YouTube fullscreen toggle")
        handler.postDelayed({ pollYouTubeFullscreen(1) }, 400)
    }

    private fun pollYouTubeFullscreen(attempt: Int) {
        if (!isWaitingForYouTubeFullscreen || attempt > 8) {
            isWaitingForYouTubeFullscreen = false
            return
        }
        if (!attemptYouTubeFullscreen(attempt)) {
            handler.postDelayed({ pollYouTubeFullscreen(attempt + 1) }, 450)
        }
    }

    private fun attemptYouTubeFullscreen(attempt: Int): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val fsIds = listOf(
            "com.google.android.youtube:id/fullscreen_button",
            "app.revanced.android.youtube:id/fullscreen_button"
        )
        for (id in fsIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (clickNodeAndTap(node)) {
                    Log.i(TAG, "Clicked YouTube fullscreen button by ID: $id")
                    isWaitingForYouTubeFullscreen = false
                    return true
                }
            }
        }

        val descMatch = findByContentDescriptionContains(
            rootNode,
            listOf("enter full screen", "full screen", "fullscreen")
        )
        if (descMatch != null && clickNodeAndTap(descMatch)) {
            Log.i(TAG, "Clicked YouTube fullscreen via content description fallback")
            isWaitingForYouTubeFullscreen = false
            return true
        }

        // The fullscreen control is usually hidden until the player controls are revealed.
        // Tap the center of the screen on the first couple attempts to reveal them, then retry.
        if (attempt <= 3) {
            val rect = Rect()
            rootNode.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }

        return false
    }

    private fun findByContentDescriptionContains(node: AccessibilityNodeInfo, phrases: List<String>): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.isNotBlank() && phrases.any { desc.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByContentDescriptionContains(child, phrases)
            if (result != null) return result
        }
        return null
    }

    // -------------------------------------------------------------
    // WhatsApp Delete Message (Delete for Everyone / Delete for Me)
    // -------------------------------------------------------------

    fun armWhatsAppDelete(forEveryone: Boolean) {
        isWaitingForWhatsAppDelete = true
        deleteForEveryone = forEveryone
        whatsAppDeleteArmTime = System.currentTimeMillis()
        deleteStage = 0
        Log.i(TAG, "Armed for WhatsApp delete message (forEveryone=$forEveryone)")
        handler.postDelayed({ pollWhatsAppDelete(1) }, 900)
    }

    private fun pollWhatsAppDelete(attempt: Int) {
        if (!isWaitingForWhatsAppDelete || attempt > 20) {
            isWaitingForWhatsAppDelete = false
            return
        }
        val done = attemptWhatsAppDeleteAction()
        if (!done) {
            handler.postDelayed({ pollWhatsAppDelete(attempt + 1) }, 550)
        }
    }

    private fun attemptWhatsAppDeleteAction(): Boolean {
        val rootNode = getActiveRootNode() ?: return false

        return when (deleteStage) {
            0 -> {
                // Stage 0: long-press the most recent message bubble to open the selection menu.
                val messageIds = listOf(
                    "com.whatsapp:id/message_text",
                    "com.whatsapp.w4b:id/message_text",
                    "com.whatsapp:id/conversation_text_row",
                    "com.whatsapp.w4b:id/conversation_text_row"
                )
                var target: AccessibilityNodeInfo? = null
                for (id in messageIds) {
                    val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                    if (nodes.isNotEmpty()) target = nodes.last()
                }
                if (target == null) {
                    target = findLastMessageBubble(rootNode)
                }
                if (target != null) {
                    var longPressed = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    if (!longPressed) {
                        val rect = Rect()
                        target.getBoundsInScreen(rect)
                        if (rect.width() > 0 && rect.height() > 0) {
                            longPressAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                            longPressed = true
                        }
                    }
                    if (longPressed) {
                        Log.i(TAG, "Long-pressed last WhatsApp message bubble")
                        deleteStage = 1
                    }
                }
                false
            }
            1 -> {
                // Stage 1: tap the delete (trash) icon in the selection action bar.
                val deleteIds = listOf(
                    "com.whatsapp:id/menuitem_delete",
                    "com.whatsapp.w4b:id/menuitem_delete"
                )
                var clicked = false
                for (id in deleteIds) {
                    val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                    for (node in nodes) {
                        if (clickNodeAndTap(node)) {
                            Log.i(TAG, "Clicked WhatsApp delete icon")
                            clicked = true
                            break
                        }
                    }
                    if (clicked) break
                }
                if (!clicked) {
                    val descNode = findByContentDescriptionContains(rootNode, listOf("delete"))
                    if (descNode != null && clickNodeAndTap(descNode)) {
                        Log.i(TAG, "Clicked WhatsApp delete icon via content description")
                        clicked = true
                    }
                }
                if (clicked) deleteStage = 2
                false
            }
            2 -> {
                // Stage 2: choose "Delete for everyone" or "Delete for me" in the dialog.
                val optionText = if (deleteForEveryone) "Delete for everyone" else "Delete for me"
                var clicked = clickFirstMatchingText(rootNode, optionText)
                if (!clicked && deleteForEveryone) {
                    // Some WhatsApp versions phrase it slightly differently.
                    clicked = clickFirstMatchingText(rootNode, "everyone")
                }
                if (!clicked && !deleteForEveryone) {
                    clicked = clickFirstMatchingText(rootNode, "delete for me")
                }
                if (clicked) {
                    Log.i(TAG, "Selected WhatsApp delete option: $optionText")
                    deleteStage = 3
                }
                false
            }
            3 -> {
                // Stage 3: some versions show one more confirmation ("Delete") button.
                val confirmed = clickFirstMatchingText(rootNode, "delete") ||
                    rootNode.findAccessibilityNodeInfosByViewId("android:id/button1").any { clickNodeAndTap(it) }
                if (confirmed) {
                    Log.i(TAG, "Confirmed WhatsApp message deletion")
                }
                isWaitingForWhatsAppDelete = false
                true
            }
            else -> {
                isWaitingForWhatsAppDelete = false
                true
            }
        }
    }

    private fun findLastMessageBubble(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        val viewId = node.viewIdResourceName ?: ""
        if (viewId.contains("message_text") || viewId.contains("conversation_text_row")) {
            result = node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childResult = findLastMessageBubble(child)
            if (childResult != null) result = childResult
        }
        return result
    }

    private fun clickFirstMatchingText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable || node.parent?.isClickable == true) {
                if (clickNodeAndTap(node)) return true
            }
        }
        return false
    }

    private fun longPressAt(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 700))
                    .build()
                dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                Log.d(TAG, "Long-press gesture failed: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------
    // Click & Touch Helpers
    // -------------------------------------------------------------

    fun clickNodeAndTap(node: AccessibilityNodeInfo): Boolean {
        // Step 1: Attempt programmatic accessibility click on the node directly
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "Programmatic ACTION_CLICK succeeded on node")
            return true
        }

        // Step 2: Attempt programmatic accessibility click on clickable parent hierarchy
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "Programmatic ACTION_CLICK succeeded on parent at depth $depth")
                return true
            }
            parent = parent.parent
            depth++
        }

        // Step 3: ONLY fallback to coordinate gesture tap if programmatic clicks failed or were not clickable
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            Log.d(TAG, "Falling back to coordinate gesture tap at center: (${rect.centerX()}, ${rect.centerY()})")
            return clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        }

        return false
    }

    fun clickAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
                return dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                Log.d(TAG, "Gesture click at ($x, $y) failed: ${e.message}")
            }
        }
        return false
    }

    // ---------------------------------------------------------------
    // Reference YouTube & Accessibility Actions
    // ---------------------------------------------------------------

    /** Taps the "Skip Ad" / "Skip Ads" button if currently visible. Returns true if found and tapped. */
    fun skipAd(): Boolean = clickNodeMatching("skip ad")

    /** Taps the Like button on the currently open video. */
    fun likeVideo(): Boolean = clickNodeMatching("like", exclude = "dislike")

    /** Taps the Subscribe button on the current channel/video. */
    fun subscribeChannel(): Boolean = clickNodeMatching("subscribe")

    /**
     * Opens the channel page by tapping the channel avatar/name row under the
     * video (NOT the "Subscribe" button).
     */
    fun openChannel(): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectByResourceId(root, "channel", candidates)
        val target = candidates
            .filter { it.isClickable }
            .minByOrNull { nodeLabel(it).length }
        if (target != null) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        return clickNodeMatching("channel", exclude = "subscribe")
    }

    /**
     * Taps the fullscreen toggle button (works for both entering and exiting fullscreen).
     */
    fun toggleFullscreen(): Boolean {
        if (clickNodeMatching("full screen", "fullscreen")) return true

        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectByResourceId(root, "fullscreen", candidates)
        val target = candidates.firstOrNull { it.isClickable } ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Auto-clicks first video result when YouTube search opens. */
    fun clickFirstVideoResult(): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickableVideoNodes(root, candidates)
        if (candidates.isNotEmpty()) {
            return candidates.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return tapAtPercentage(50f, 30f)
    }

    private fun collectClickableVideoNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isClickable && (node.className?.contains("ViewGroup") == true || node.className?.contains("RelativeLayout") == true || node.className?.contains("FrameLayout") == true)) {
            val label = nodeLabel(node)
            if (label.length > 20) out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableVideoNodes(child, out)
        }
    }

    private fun clickNodeMatching(vararg keywords: String, exclude: String? = null): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val lowerKeywords = keywords.map { it.lowercase() }
            val lowerExclude = exclude?.lowercase()

            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectMatches(root, lowerKeywords, lowerExclude, candidates)
            if (candidates.isEmpty()) return false

            val target = candidates.minByOrNull { nodeLabel(it).length } ?: return false
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.w(TAG, "clickNodeMatching exception", e)
            false
        }
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: ""

    private fun collectMatches(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        exclude: String?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            val label = nodeLabel(node).lowercase()
            if (label.isNotBlank()) {
                val matches = keywords.any { label.contains(it) } && (exclude == null || !label.contains(exclude))
                if (matches && (node.isClickable || node.isCheckable)) {
                    out.add(node)
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectMatches(child, keywords, exclude, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "collectMatches exception", e)
        }
    }

    private fun collectByResourceId(
        node: AccessibilityNodeInfo,
        needle: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            val id = node.viewIdResourceName?.lowercase() ?: ""
            if (id.contains(needle)) out.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectByResourceId(child, needle, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "collectByResourceId exception", e)
        }
    }

    // ---------------------------------------------------------------
    // WhatsApp Clean Automation (Fixed: Immediately stops, never clicks mic)
    // ---------------------------------------------------------------

    /**
     * Monitors the screen after a WhatsApp deep-link is launched and automatically taps the Send button
     * (content-desc="Send" or id="send" or send icon) for hands-free message delivery.
     * Stops polling immediately after clicking to avoid clicking the microphone icon.
     */
    fun scheduleWhatsAppAutoSend() {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed(object : Runnable {
            var attempts = 0
            override fun run() {
                val root = rootInActiveWindow
                if (root != null) {
                    val sendNode = findWhatsAppSendButton(root)
                    if (sendNode != null) {
                        tapNodeOrParent(sendNode)
                        Log.d(TAG, "Successfully tapped WhatsApp send button on attempt $attempts. Disarming.")
                        return
                    }
                }

                attempts++
                if (attempts < 14) {
                    mainHandler.postDelayed(this, 300)
                }
            }
        }, 500)
    }

    private fun findWhatsAppSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (desc == "send" || desc == "send message" || text == "send" || viewId.endsWith(":id/send")) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findWhatsAppSendButton(child)
            if (result != null) return result
        }
        return null
    }

    fun scheduleWhatsAppCallAutoTap(callType: String) {
        val isVideo = callType.lowercase().contains("video")
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed(object : Runnable {
            var attempts = 0
            var callButtonTapped = false

            override fun run() {
                val root = rootInActiveWindow
                if (root != null) {
                    if (!callButtonTapped) {
                        val callNode = findWhatsAppCallButton(root, isVideo)
                        if (callNode != null) {
                            val success = tapDirectOrImmediateClickable(callNode)
                            if (success) {
                                callButtonTapped = true
                                Log.d(TAG, "Tapped WhatsApp call button, watching for confirmation dialog...")
                            }
                        }
                    } else {
                        val confirmNode = findWhatsAppCallConfirmButton(root)
                        if (confirmNode != null) {
                            tapNodeOrParent(confirmNode)
                            Log.d(TAG, "Tapped WhatsApp Call confirmation dialog button!")
                            return
                        }
                    }
                }

                attempts++
                if (attempts < 16) {
                    mainHandler.postDelayed(this, 350)
                }
            }
        }, 500)
    }

    private fun tapDirectOrImmediateClickable(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) return true
        }
        val parent = node.parent
        if (parent != null && parent.isClickable) {
            val parentId = parent.viewIdResourceName?.lowercase() ?: ""
            if (!parentId.contains("header") && !parentId.contains("action_bar") && !parentId.contains("toolbar")) {
                val ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
            }
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            return tapAtAbsoluteCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        return false
    }

    private fun findWhatsAppCallConfirmButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (viewId.endsWith(":id/button1") ||
            text == "call" || text == "video call" || text == "start call" || text == "कॉल करें" || text == "कॉल" ||
            desc == "call" || desc == "video call" || desc == "start call") {
            if (node.isClickable || node.parent?.isClickable == true) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findWhatsAppCallConfirmButton(child)
            if (res != null) return res
        }
        return null
    }

    private fun findWhatsAppCallButton(node: AccessibilityNodeInfo, isVideo: Boolean): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (desc.contains("profile") || desc.contains("photo") || desc.contains("picture") ||
            desc.contains("avatar") || desc.contains("header") || desc.contains("navigate up") ||
            desc.contains("back") || viewId.contains("picture") || viewId.contains("avatar")) {
            return null
        }

        val isTarget = if (isVideo) {
            (desc.contains("video call") || viewId.endsWith(":id/video_call") || viewId.endsWith(":id/menuitem_video_call")) &&
                    !desc.contains("voice")
        } else {
            (desc == "voice call" || desc == "call" || desc.startsWith("voice call") ||
                    viewId.endsWith(":id/voice_call") || viewId.endsWith(":id/menuitem_call")) &&
                    !desc.contains("video")
        }

        if (isTarget && (node.isClickable || node.parent?.isClickable == true)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findWhatsAppCallButton(child, isVideo)
            if (result != null) return result
        }
        return null
    }

    private fun tapNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
            }
            current = current.parent
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            return tapAtAbsoluteCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        return false
    }

    fun tapAtPercentage(xPercent: Float, yPercent: Float): Boolean {
        val dm = resources.displayMetrics
        val x = (xPercent / 100f) * dm.widthPixels
        val y = (yPercent / 100f) * dm.heightPixels
        clickAt(x, y)
        return true
    }

    fun tapAtAbsoluteCoordinates(x: Float, y: Float): Boolean {
        clickAt(x, y)
        return true
    }

    fun clickNodeWithText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickFirstMatchingText(root, text)
    }

    fun typeText(textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    fun getActiveRootNode(): AccessibilityNodeInfo? {
        // Prefer Android's active-window root. If unavailable, only accept a window
        // that Android explicitly marks focused/active; never guess with an arbitrary
        // application window because dialogs, keyboards, and split-screen can reorder it.
        val active = rootInActiveWindow
        if (active != null) return active

        // 2. Fallback to focused application window
        try {
            val focusedAppWin = windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            }
            if (focusedAppWin?.root != null) return focusedAppWin.root

            // 3. Fallback to active application window
            val activeAppWin = windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
            }
            if (activeAppWin?.root != null) return activeAppWin.root

            return null
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving active root node across windows: ${e.message}")
            return null
        }
    }

    fun getAppRootNode(): AccessibilityNodeInfo? {
        return getActiveRootNode()
    }

    fun performClickAtRect(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        // Re-resolve against a fresh active root immediately before mutation. Prefer the
        // accessibility action; use a coordinate gesture only when no actionable node
        // resolves or ACTION_CLICK explicitly returns false.
        val root = getActiveRootNode()
        // Chrome's WebView targets often expose a clickable accessibility wrapper that
        // acknowledges ACTION_CLICK without forwarding the tap to the page control.
        // Prefer a real screen gesture there, then retain the node action as fallback.
        if (root?.packageName?.toString() == "com.android.chrome" && clickAt(cx, cy)) return true
        var node = root?.let { findNodeCoveringRect(it, rect) }
        while (node != null) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isVisibleToUser && node.isClickable && bounds.contains(cx.toInt(), cy.toInt())) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                break
            }
            node = node.parent
        }
        Log.i(TAG, "Using coordinate fallback at ($cx, $cy) [bounds: $rect]")
        return clickAt(cx, cy)
    }

    fun performSmartScroll(direction: String, distance: String = "small"): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()

        // Calculate delta based on distance:
        // "small" = gentle line-by-line scroll (~18% of screen height, moving 1-2 rows at a time with 0 fling)
        // "medium" = ~30% of screen height
        // "large" = ~45% of screen height
        val deltaY = when (distance.lowercase()) {
            "small" -> height * 0.18f
            "large" -> height * 0.45f
            else -> height * 0.28f
        }
        val durationMs = when (distance.lowercase()) {
            "small" -> 600L  // Slow, controlled drag with ZERO fling momentum
            "large" -> 400L
            else -> 550L
        }

        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "down" -> listOf(width * 0.5f, height * 0.58f, width * 0.5f, height * 0.58f - deltaY)
            "up" -> listOf(width * 0.5f, height * 0.42f, width * 0.5f, height * 0.42f + deltaY)
            "left" -> listOf(width * 0.75f, height * 0.5f, width * 0.25f, height * 0.5f)
            "right" -> listOf(width * 0.25f, height * 0.5f, width * 0.75f, height * 0.5f)
            else -> listOf(width * 0.5f, height * 0.58f, width * 0.5f, height * 0.58f - deltaY)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
                val dispatched = dispatchGesture(gesture, null, null)
                Log.i(TAG, "Dispatched scroll $direction gesture (distance=$distance, deltaY=$deltaY, duration=${durationMs}ms): $dispatched")
                return dispatched
            } catch (e: Exception) {
                Log.e(TAG, "Scroll gesture failed: ${e.message}")
            }
        }
        return false
    }

    fun findNodeCoveringRect(root: AccessibilityNodeInfo, targetRect: Rect): AccessibilityNodeInfo? {
        val temp = Rect()
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.getBoundsInScreen(temp)
            if (Rect.intersects(temp, targetRect)) {
                for (i in (node.childCount - 1) downTo 0) {
                    val child = node.getChild(i) ?: continue
                    val deep = search(child)
                    if (deep != null) return deep
                }
                return node
            }
            return null
        }
        return search(root)
    }

    fun findNodeWithInputFocus(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findNodeWithInputFocus(child)
            if (res != null) return res
        }
        return null
    }

    fun verifyNodeText(node: AccessibilityNodeInfo?, expectedText: String): Boolean {
        if (node == null) return false
        val current = node.text?.toString()?.trim() ?: ""
        val exp = expectedText.trim()
        if (exp.isEmpty()) return true
        return current.contains(exp, ignoreCase = true) ||
               (current.isNotEmpty() && exp.contains(current, ignoreCase = true) && current.length >= 3)
    }

    fun smartTypeText(textToType: String, targetRect: Rect? = null, submit: Boolean = false): Boolean {
        lastTypeVerified = false
        if (textToType.isBlank()) {
            Log.w(TAG, "smartTypeText refused blank input")
            return false
        }
        Log.i(TAG, "smartTypeText starting (length=${textToType.length}, targeted=${targetRect != null}, submit=$submit)")

        if (targetRect != null && targetRect.width() > 0 && targetRect.height() > 0) {
            if (!performClickAtRect(targetRect)) return false
            Thread.sleep(100)
        }

        val appRoot = getAppRootNode() ?: getActiveRootNode() ?: return false
        val inBrowser = (appRoot.packageName?.toString() ?: "").let {
            it.contains("chrome", ignoreCase = true) || it.contains("browser", ignoreCase = true)
        }

        fun editableAt(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (targetRect != null && targetRect.width() > 0) {
                var node = findNodeCoveringRect(root, targetRect)
                while (node != null) {
                    if (node.isEditable || node.className?.toString()?.contains("EditText", true) == true) return node
                    node = node.parent
                }
                return null
            }
            return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findNodeWithInputFocus(root)
                ?: findFirstEditableNode(root)
        }

        var target = editableAt(appRoot)
        if (target != null && isUrlBarNode(target)) {
            Log.w(TAG, "smartTypeText refused browser URL/address bar")
            return false
        }
        if (target == null) {
            Log.w(TAG, "smartTypeText found no verified editable target; no text was entered")
            return false
        }

        fun currentTarget(): AccessibilityNodeInfo? {
            val freshRoot = getActiveRootNode() ?: getAppRootNode() ?: return null
            return editableAt(freshRoot)
        }
        fun textEqualsExpected(node: AccessibilityNodeInfo?): Boolean {
            val current = node?.text?.toString()?.replace("\r\n", "\n")?.trim().orEmpty()
            val expected = textToType.replace("\r\n", "\n").trim()
            return current == expected
        }
        fun setValue(node: AccessibilityNodeInfo, value: String): Boolean {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        // Repeated model tool calls become idempotent: matching text is success, never paste it again.
        if (textEqualsExpected(target)) {
            lastTypeVerified = true
            Log.i(TAG, "smartTypeText skipped duplicate request: the exact text is already present")
        } else {
            var setAccepted = false
            try {
                setAccepted = setValue(target, textToType)
            } catch (e: Exception) {
                Log.w(TAG, "ACTION_SET_TEXT failed: ${e.message}")
            }

            if (setAccepted) {
                Thread.sleep(180)
                target = currentTarget()
                lastTypeVerified = textEqualsExpected(target)
                if (!lastTypeVerified) {
                    // A successful SET_TEXT with no readable confirmation may already have changed
                    // the UI. Do not paste on top of it; that was the source of repeated text.
                    Log.w(TAG, "Text set was not read back exactly; refusing append-style fallback")
                    return false
                }
            } else {
                // Paste fallback is safe only after the current field has been verified empty.
                // Clearing is done with ACTION_SET_TEXT, never by tapping an ambiguous clear icon.
                val fresh = currentTarget() ?: target
                val currentText = fresh.text?.toString().orEmpty()
                if (currentText.isNotBlank()) {
                    val cleared = try { setValue(fresh, "") } catch (_: Exception) { false }
                    Thread.sleep(120)
                    target = currentTarget()
                    if (!cleared || !target?.text?.toString().isNullOrBlank()) {
                        Log.w(TAG, "Existing input could not be safely cleared; refusing to append text")
                        return false
                    }
                } else {
                    target = fresh
                }

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                try {
                    clipboard?.setPrimaryClip(ClipData.newPlainText("JarvisInput", textToType))
                    val pasteTarget = currentTarget() ?: target ?: return false
                    if (isUrlBarNode(pasteTarget)) return false
                    val pasted = pasteTarget.performAction(AccessibilityNodeInfo.ACTION_PASTE) ||
                        pasteTarget.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id)
                    if (!pasted) {
                        Log.w(TAG, "Safe paste unavailable; no second typing strategy was attempted")
                        return false
                    }
                    Thread.sleep(180)
                    lastTypeVerified = textEqualsExpected(currentTarget())
                    if (!lastTypeVerified) {
                        Log.w(TAG, "Pasted text could not be verified exactly; refusing to retry and duplicate it")
                        return false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Safe paste failed: ${e.message}")
                    return false
                }
            }
        }

        // Never auto-tap Send/Submit as a side effect of typing. Search/submit actions
        // must be a separate, freshly inspected click; the service enforces send intent.
        if (submit) Log.i(TAG, "Submit requested after typing; waiting for an explicit grounded submit action")
        return lastTypeVerified
    }

    private fun performLongPressAndPaste(targetRect: Rect): Boolean {
        val clickX = targetRect.centerX().toFloat()
        val clickY = targetRect.centerY().toFloat()
        val path = Path().apply { moveTo(clickX, clickY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Long-press gesture dispatch failed: ${e.message}")
            return false
        }

        // Active polling: wait for Android floating text selection toolbar to appear (up to 800ms)
        val startTime = System.currentTimeMillis()
        var pasteNode: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() - startTime < 800L) {
            try { Thread.sleep(60) } catch (_: Exception) {}
            pasteNode = findPasteButtonAcrossWindows()
            if (pasteNode != null) break
        }

        if (pasteNode != null) {
            Log.i(TAG, "Found native Paste popup menu item: ${pasteNode.text ?: pasteNode.contentDescription}")
            val clicked = clickNodeAndTap(pasteNode)
            Thread.sleep(120)
            return clicked
        }
        return false
    }

    private fun findPasteButtonAcrossWindows(): AccessibilityNodeInfo? {
        val pasteKeywords = listOf("paste", "चिपकाएं", "पेस्ट", "paste from clipboard", "insert")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (window in windows) {
                    val root = window.root ?: continue
                    val found = searchNodeForKeywords(root, pasteKeywords)
                    if (found != null) return found
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error inspecting windows for paste button: ${e.message}")
        }
        val appRoot = getAppRootNode() ?: getActiveRootNode() ?: return null
        return searchNodeForKeywords(appRoot, pasteKeywords)
    }

    private fun searchNodeForKeywords(node: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val vid = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

        for (kw in keywords) {
            if (text == kw || desc == kw || text.contains(kw) || desc.contains(kw) || vid.contains(kw)) {
                if (node.isClickable || node.parent?.isClickable == true) {
                    return if (node.isClickable) node else node.parent
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeForKeywords(child, keywords)
            if (found != null) return found
        }
        return null
    }

    /**
     * Searches across ALL active windows — specifically including TYPE_INPUT_METHOD (Gboard) —
     * for a clipboard suggestion chip or paste action button.
     */
    private fun findClipboardChipAcrossAllWindows(textToType: String): AccessibilityNodeInfo? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Priority 1: Check Input Method window (Gboard/keyboard strip)
                for (window in windows) {
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val root = window.root ?: continue
                        val found = findClipboardSuggestionChip(root, textToType)
                        if (found != null) {
                            Log.i(TAG, "Found clipboard suggestion chip in TYPE_INPUT_METHOD window!")
                            return found
                        }
                    }
                }
                // NOTE: other windows (Chrome page, menus, omnibox suggestions) are intentionally NOT searched:
                // matching generic "chip"/"suggestion" ids there caused random mis-taps.
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error searching windows for clipboard chip: ${e.message}")
        }
        return null
    }

    private fun findClipboardSuggestionChip(node: AccessibilityNodeInfo, textToType: String): AccessibilityNodeInfo? {
        val snippet = textToType.take(8).lowercase(Locale.ROOT)
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val vid = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

        val isChipMatch = (vid.contains("clipboard") || desc.contains("clipboard") ||
            (snippet.length >= 3 && (text.contains(snippet) || desc.contains(snippet))))

        if (isChipMatch && (node.isClickable || node.parent?.isClickable == true)) {
            return if (node.isClickable) node else node.parent
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClipboardSuggestionChip(child, textToType)
            if (found != null) return found
        }
        return null
    }

    /** True for Chrome's address bar / omnibox / NTP search box. Page prompts must NEVER be typed here. */
    fun isUrlBarNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val vid = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        return vid.contains("url_bar") || vid.contains("omnibox") ||
               vid.contains("location_bar") || vid.contains("search_box_text")
    }

    /** True while the soft keyboard (an input-method window) is on screen. */
    fun isKeyboardVisible(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            false
        }
    }

    /** Result of the last smartTypeText call: true only if the typed text was READ BACK from the screen. */
    @Volatile
    var lastTypeVerified: Boolean = false

    /**
     * Reads the real screen (application windows only; NOT the keyboard, so the Gboard clipboard chip
     * cannot cause a false positive; NOT the URL bar) and checks whether the start of [text] is visible.
     * Polls for up to ~1.2s because Chrome syncs web inputs to the accessibility tree with a delay.
     */
    fun verifyTextVisibleAnywhere(text: String): Boolean {
        val snippet = text.trim().take(24).lowercase(Locale.ROOT)
        if (snippet.length < 3) return true
        fun scan(n: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 60) return false
            if (!isUrlBarNode(n)) {
                val t = n.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val d = n.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (t.contains(snippet) || d.contains(snippet)) return true
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (scan(c, depth + 1)) return true
            }
            return false
        }
        repeat(4) {
            try {
                for (w in windows) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val r = w.root ?: continue
                    if (scan(r, 0)) return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "verifyTextVisibleAnywhere error: ${e.message}")
            }
            try { Thread.sleep(300) } catch (_: Exception) {}
        }
        return false
    }

    fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if ((node.isEditable || node.className?.toString()?.contains("EditText") == true) && !isUrlBarNode(node)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findFirstEditableNode(child)
            if (res != null) return res
        }
        return null
    }

    private fun findSearchOrSubmitButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findSubmitButtonInTree(node) ?: findSendButtonByPosition()
    }

    /** Depth-first search for a named send/generate/submit button, explicitly excluding attach/add/+ buttons. */
    private fun findSubmitButtonInTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val vid = node.viewIdResourceName?.lowercase() ?: ""

        // EXPLICITLY EXCLUDE attachment / add-media / plus buttons — these are NOT submit buttons
        val isAttachOrAddButton = desc.contains("attach") || desc.contains("add file") ||
                desc.contains("add media") || desc.contains("add image") ||
                desc.contains("add attachment") || desc.contains("add photo") ||
                desc.contains("upload") || desc.contains("insert") ||
                text == "+" || text == "add" ||
                vid.contains("attach") || vid.contains("add_media") ||
                vid.contains("add_file") || vid.contains("add_photo") ||
                vid.contains("media_button") || vid.contains("plus_button")
        if (isAttachOrAddButton) {
            // Don't search this node but DO search its siblings via parent — skip to children check
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findSubmitButtonInTree(child)
                if (found != null) return found
            }
            return null
        }

        val isMatch = desc.contains("send") || desc.contains("submit") || desc.contains("generate") ||
                desc.contains("create image") || desc.contains("create video") ||
                desc.contains("run") || desc.contains("done") || desc.contains("apply") ||
                desc.contains("go") || desc.contains("search") || desc.contains("enter") ||
                desc == "→" || desc == "▶" || desc.contains("arrow") ||
                text == "send" || text == "go" || text == "generate" || text == "create" ||
                text == "submit" || text == "done" || text == "search" || text == "run" ||
                vid.contains("send_button") || vid.contains("send_btn") ||
                vid.contains("search_button") || vid.contains("search_go_btn") ||
                vid.contains("submit_button") || vid.contains("generate_button") ||
                vid.contains("action_button") || vid.contains("btn_send")

        if (isMatch && (node.isClickable || node.parent?.isClickable == true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSubmitButtonInTree(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Position-based fallback: finds the send/generate button by its typical screen position
     * (small clickable button in the bottom-right area). Used when Gemini's arrow button has
     * no text or contentDescription that matches keyword search.
     */
    private fun findSendButtonByPosition(): AccessibilityNodeInfo? {
        val root = getAppRootNode() ?: getActiveRootNode() ?: return null
        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels

        // Bottom-right quadrant: right 35% width, bottom 25% height
        val searchLeft = (screenW * 0.65f).toInt()
        val searchTop = (screenH * 0.75f).toInt()

        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()

        fun collectCandidates(node: AccessibilityNodeInfo) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isClickable && !node.isEditable &&
                bounds.left >= searchLeft && bounds.top >= searchTop &&
                bounds.width() in 30..300 && bounds.height() in 30..200) {
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val vid = node.viewIdResourceName?.lowercase() ?: ""
                // Exclude attach/voice/mic buttons
                val isExcluded = desc.contains("attach") || desc.contains("mic") ||
                        desc.contains("voice") || desc.contains("emoji") ||
                        desc.contains("camera") || desc.contains("sticker") ||
                        vid.contains("mic") || vid.contains("voice") || vid.contains("attach")
                if (!isExcluded) {
                    candidates.add(Pair(node, bounds))
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collectCandidates(it) }
            }
        }
        collectCandidates(root)

        // Pick the rightmost button (send/generate is always at far right)
        return candidates.maxByOrNull { it.second.right }?.first
    }

    fun performSystemGesture(gesture: String): Boolean {
        return when (gesture.lowercase()) {
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> false
        }
    }
}
