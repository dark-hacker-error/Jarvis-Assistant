package com.jarvis.assistant.ui.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.MotionEvent
import android.view.Gravity
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.jarvis.assistant.JarvisApp
import android.view.ViewGroup
import com.jarvis.assistant.automation.agent.AgentTaskSnapshot
import com.jarvis.assistant.automation.agent.TaskStepStatus

/** Small task HUD owned by the foreground conversation service. */
class TaskProgressOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    private val root = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
    }
    private val pill = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = GradientDrawable().apply {
            setColor(Color.argb(146, 8, 9, 12))
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), Color.argb(65, 225, 230, 238))
        }
        elevation = dp(12).toFloat()
    }
    private val header = TextView(context).apply {
        text = "JARVIS  ⋮⋮"
        setTextColor(Color.rgb(215, 220, 228))
        textSize = 10f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(5))
        contentDescription = "Drag JARVIS task overlay anywhere on screen"
    }
    private val title = label(11f, true).apply { maxLines = 1 }
    private val progressText = label(11f, true).apply {
        setTextColor(Color.rgb(190, 196, 206))
        textSize = 9f
        setPadding(0, dp(2), 0, 0)
    }
    private val stepRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(8, 9, 12))
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(55, 230, 234, 240))
    }
    private val avatar = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
        }
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        visibility = View.VISIBLE
    }
    private val params = WindowManager.LayoutParams(
        minOf(dp(236), context.resources.displayMetrics.widthPixels - dp(16)),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        x = dp(8)
        y = dp(90)
    }
    private var attached = false
    private var avatarMode = true
    private var loadedAvatar = ""
    private var displayedTaskId: String? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var currentEdge = ""
    private var dragging = false
    private var lastTapAt = 0L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        pill.visibility = View.GONE
        avatar.visibility = View.VISIBLE
        pill.addView(header)
        pill.addView(title, matchWrap())
        pill.addView(stepRows, matchWrap())
        pill.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4); bottomMargin = dp(1) })
        pill.addView(progressText, matchWrap())
        root.addView(pill, FrameLayout.LayoutParams(-1, -2))
        root.addView(avatar, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))
        pill.isClickable = true
        pill.setOnTouchListener { _, event -> handleOverlayTouch(event) }
        avatar.setOnTouchListener { _, event -> handleOverlayTouch(event) }
    }

    fun update(snapshot: AgentTaskSnapshot?, showIdleAvatar: Boolean = false): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            remove()
            return false
        }
        val preferences = JarvisApp.instance.preferences
        if (snapshot == null && !showIdleAvatar && preferences.taskOverlayMode != "always") {
            remove()
            displayedTaskId = null
            return true
        }
        if (!preferences.isTaskOverlayEnabled) {
            remove()
            return true
        }
        val selectedAvatar = preferences.taskOverlayAvatar
        if (selectedAvatar != loadedAvatar) {
            loadedAvatar = selectedAvatar
            avatar.loadUrl("https://appassets.androidplatform.net/assets/overlay-toys/$selectedAvatar.html")
        }
        val nextTaskId = snapshot?.taskId
        if (displayedTaskId != nextTaskId) {
            displayedTaskId = nextTaskId
            avatarMode = true
        }
        if (!attached) {
            try {
                if (preferences.taskOverlayFreePosition && preferences.taskOverlayX >= 0 && preferences.taskOverlayY >= 0) {
                    params.gravity = Gravity.TOP or Gravity.LEFT
                    params.x = preferences.taskOverlayX
                    params.y = preferences.taskOverlayY
                    clampPosition()
                    currentEdge = preferences.taskOverlayEdge
                }
                windowManager.addView(root, params)
                attached = true
            } catch (_: Exception) {
                attached = false
                return false
            }
        }
        if (currentEdge != preferences.taskOverlayEdge) applyEdge(preferences.taskOverlayEdge)
        if (snapshot == null) renderIdle() else render(snapshot)
        applyMode()
        return true
    }

    fun remove() {
        if (attached) {
            try { windowManager.removeView(root) } catch (_: Exception) {}
            attached = false
        }
    }

    private fun render(snapshot: AgentTaskSnapshot) {
        progress.visibility = View.VISIBLE
        title.text = snapshot.originalGoal
        progress.progress = snapshot.progressPercentage.coerceIn(0, 100)
        val completed = snapshot.steps.count { it.status == TaskStepStatus.COMPLETED }
        progressText.text = "$completed/${snapshot.steps.size} done  ·  ${snapshot.progressPercentage}%"
        stepRows.removeAllViews()
        val activeIndex = snapshot.steps.indexOfFirst { it.stepId == snapshot.currentStepId }.coerceAtLeast(0)
        val first = (activeIndex - 2).coerceAtLeast(0)
        snapshot.steps.drop(first).take(4).forEach { step ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val active = step.status in setOf(TaskStepStatus.RUNNING, TaskStepStatus.VERIFYING, TaskStepStatus.WAITING_FOR_UI, TaskStepStatus.RECOVERING)
            val marker: View = if (active) {
                ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(205, 210, 218))
                }
            } else TextView(context).apply {
                text = if (step.status == TaskStepStatus.COMPLETED) "✓" else if (step.status in setOf(TaskStepStatus.FAILED, TaskStepStatus.BLOCKED)) "!" else "·"
                setTextColor(if (step.status == TaskStepStatus.COMPLETED) Color.rgb(175, 210, 188) else Color.rgb(165, 171, 181))
                textSize = 10f
                gravity = Gravity.CENTER
            }
            row.addView(marker, LinearLayout.LayoutParams(dp(12), dp(12)))
            row.addView(label(9f, active).apply {
                text = step.title
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                alpha = if (step.status == TaskStepStatus.COMPLETED) 0.72f else 1f
                setPadding(dp(5), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, dp(14), 1f))
            stepRows.addView(row, LinearLayout.LayoutParams(-1, dp(15)))
        }
        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun renderIdle() {
        title.text = "JARVIS"
        stepRows.removeAllViews()
        stepRows.addView(label(9f, false).apply { text = "Ready · Double-tap for task status" }, matchWrap())
        progress.visibility = View.GONE
        progressText.text = "Double-tap for status"
        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun handleOverlayTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                startX = location[0]
                startY = location[1]
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (dragging || kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                    dragging = true
                    params.gravity = Gravity.TOP or Gravity.LEFT
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    clampPosition()
                    try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    clampPosition()
                    val preferences = JarvisApp.instance.preferences
                    preferences.taskOverlayFreePosition = true
                    preferences.taskOverlayX = params.x
                    preferences.taskOverlayY = params.y
                    return true
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTapAt <= 360L) {
                    lastTapAt = 0L
                    toggleAvatarMode()
                    return true
                }
                lastTapAt = now
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return false
            }
        }
        return false
    }

    private fun applyEdge(edge: String) {
        currentEdge = edge
        when (edge) {
            "left" -> { params.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; params.x = dp(8); params.y = 0 }
            "top" -> { params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; params.x = 0; params.y = dp(90) }
            "bottom" -> { params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; params.x = 0; params.y = dp(90) }
            else -> { params.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; params.x = dp(8); params.y = 0 }
        }
        try { if (attached) windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun toggleAvatarMode() {
        avatarMode = !avatarMode
        applyMode()
    }

    private fun applyMode() {
        pill.visibility = if (avatarMode) View.GONE else View.VISIBLE
        avatar.visibility = if (avatarMode) View.VISIBLE else View.GONE
        params.width = if (avatarMode) dp(88) else minOf(dp(236), context.resources.displayMetrics.widthPixels - dp(16))
        params.height = if (avatarMode) dp(88) else WindowManager.LayoutParams.WRAP_CONTENT
        if (JarvisApp.instance.preferences.taskOverlayFreePosition) clampPosition()
        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun clampPosition() {
        val metrics = context.resources.displayMetrics
        val overlayWidth = root.width.takeIf { it > 0 } ?: params.width.takeIf { it > 0 } ?: dp(88)
        val overlayHeight = root.height.takeIf { it > 0 } ?: params.height.takeIf { it > 0 } ?: dp(88)
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = params.x.coerceIn(0, (metrics.widthPixels - overlayWidth).coerceAtLeast(0))
        params.y = params.y.coerceIn(dp(24), (metrics.heightPixels - overlayHeight).coerceAtLeast(dp(24)))
    }

    private fun label(size: Float, bold: Boolean) = TextView(context).apply {
        setTextColor(Color.rgb(226, 232, 240))
        textSize = size
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
