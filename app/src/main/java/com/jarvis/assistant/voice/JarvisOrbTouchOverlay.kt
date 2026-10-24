package com.jarvis.assistant.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Computes the requested bottom-centre position:
 *
 *     bottom of the orb/pill = navigation bar + 36dp
 */
object JarvisOrbMetrics {
    private const val TAG = "JarvisOrbMetrics"
    private const val BOTTOM_CLEARANCE_DP = 36f

    fun bottomOffsetPx(context: Context, navigationBarInsetPx: Int? = null): Int {
        val nav = navigationBarInsetPx ?: navigationBarHeightPx(context)
        return dp(context, BOTTOM_CLEARANCE_DP) + nav.coerceAtLeast(0)
    }

    fun navigationBarHeightPx(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val insets = wm.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                if (insets.bottom > 0) return insets.bottom
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to read window metrics: ${t.message}")
            }
        }

        val resources = context.resources
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

/**
 * Invisible, touchable 75dp circle (or 140dp x 36dp pill) that sits exactly on
 * top of the visual orb.
 *
 * Why a separate overlay?
 * The full-screen VoiceInteractionSession window is marked
 * FLAG_NOT_TOUCHABLE, so every touch outside this small target falls straight
 * through to the app underneath. This is the only way to satisfy both
 * requirements at once:
 *   - the agent can click/scroll the app below without obstruction
 *   - the orb's own circle remains tappable
 *
 * The target is purely transparent and is hidden from the accessibility tree.
 */
class JarvisOrbTouchOverlay(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "JarvisOrbTouch"
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val touchView = View(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
        setOnClickListener { onTap() }
        if (onLongPress != null) {
            isLongClickable = true
            setOnLongClickListener {
                onLongPress.invoke()
                true
            }
        }
    }

    private val params = WindowManager.LayoutParams(
        JarvisOrbMetrics.dp(context, 75f),
        JarvisOrbMetrics.dp(context, 75f),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = JarvisOrbMetrics.bottomOffsetPx(context)
    }

    private var attached = false

    fun show(mode: JarvisOrbView.Mode) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission missing; orb remains visual-only")
            return
        }
        update(mode)
        if (attached) return
        try {
            windowManager.addView(touchView, params)
            attached = true
        } catch (t: Throwable) {
            attached = false
            Log.w(TAG, "Unable to attach orb touch target: ${t.message}")
        }
    }

    fun update(mode: JarvisOrbView.Mode) {
        val width = if (mode == JarvisOrbView.Mode.WORKING) 140f else 75f
        val height = if (mode == JarvisOrbView.Mode.WORKING) 36f else 75f
        params.width = JarvisOrbMetrics.dp(context, width)
        params.height = JarvisOrbMetrics.dp(context, height)
        params.y = JarvisOrbMetrics.bottomOffsetPx(context)
        if (attached) {
            try {
                windowManager.updateViewLayout(touchView, params)
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to update orb touch target: ${t.message}")
            }
        }
    }

    fun remove() {
        if (!attached) return
        try {
            windowManager.removeView(touchView)
        } catch (_: Throwable) {
        } finally {
            attached = false
        }
    }
}
