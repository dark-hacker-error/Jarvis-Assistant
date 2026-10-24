package com.jarvis.assistant.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Main-thread owner of the floating orb.
 *
 * The visual layer and the touch target are deliberately separate windows:
 *
 *  - visual: 140 x 76 dp, TYPE_APPLICATION_OVERLAY, NOT_TOUCHABLE, so every
 *    touch outside the orb goes to the app underneath;
 *  - touch: 75 dp circle (or 140 x 36 dp working pill), also an overlay, so
 *    only the orb/pill itself is interactive.
 *
 * All WindowManager.addView/updateViewLayout/removeView calls are forced onto
 * the main looper. A single `visualAttached` guard prevents the
 * "view already has a parent" / double-attach crash.
 *
 * The controller is also usable as a fallback when the system
 * VoiceInteractionSession fails to show: the wake callback can attach the orb
 * immediately without waiting for the session window.
 */
object JarvisFloatingOrbController {

    private const val TAG = "JarvisFloatingOrb"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var visualRoot: FrameLayout? = null
    private var orbView: JarvisOrbView? = null
    private var visualParams: WindowManager.LayoutParams? = null
    private var touchOverlay: JarvisOrbTouchOverlay? = null

    private var visualAttached = false
    private var hiding = false

    @Volatile
    private var tapCallback: (() -> Unit)? = null

    @Volatile
    private var longPressCallback: (() -> Unit)? = null

    fun isShowing(): Boolean = visualAttached

    /**
     * Show the listening/working orb. Must be safe to call from any thread; the
     * actual attach is posted to the main looper.
     *
     * @return true when the visual overlay is (or is about to be) attached. A
     * false result means overlay permission is missing or WindowManager rejected
     * the window, in which case the caller can use the VoiceInteractionSession
     * content view as a visual-only fallback.
     */
    fun show(
        context: Context,
        mode: JarvisOrbView.Mode = JarvisOrbView.Mode.LISTENING,
        onTap: (() -> Unit)? = null,
        onLongPress: (() -> Unit)? = null
    ): Boolean {
        if (onTap != null) tapCallback = onTap
        if (onLongPress != null) longPressCallback = onLongPress

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context, mode, onTap, onLongPress) }
            return visualAttached
        }

        val app = context.applicationContext
        appContext = app
        windowManager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false

        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "Overlay permission is missing; floating orb not attached")
            return false
        }

        if (visualAttached) {
            hiding = false
            orbView?.setMode(mode)
            touchOverlay?.update(mode)
            return true
        }

        val wm = windowManager ?: return false
        val frame = FrameLayout(app).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val orb = JarvisOrbView(app).apply {
            alpha = 0f
            translationY = JarvisOrbMetrics.dp(app, 56f).toFloat()
        }
        frame.addView(
            orb,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val params = WindowManager.LayoutParams(
            JarvisOrbMetrics.dp(app, 140f),
            JarvisOrbMetrics.dp(app, 76f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = JarvisOrbMetrics.bottomOffsetPx(app)
        }

        return try {
            wm.addView(frame, params)
            visualRoot = frame
            orbView = orb
            visualParams = params
            visualAttached = true
            hiding = false

            orb.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.12f))
                .start()
            orb.setMode(mode)

            val touch = JarvisOrbTouchOverlay(
                context = app,
                onTap = { tapCallback?.invoke() },
                onLongPress = { longPressCallback?.invoke() }
            )
            touchOverlay = touch
            touch.show(mode)
            Log.i(TAG, "Floating orb attached on main looper")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "WindowManager.addView failed: ${t.message}", t)
            visualAttached = false
            visualRoot = null
            orbView = null
            visualParams = null
            false
        }
    }

    fun update(
        mode: JarvisOrbView.Mode,
        status: String? = null,
        amplitude: Float? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { update(mode, status, amplitude) }
            return
        }
        if (!visualAttached) return
        orbView?.setMode(mode, status)
        if (status != null) orbView?.setWorkingStatus(status)
        if (amplitude != null) orbView?.setAmplitude(amplitude)
        touchOverlay?.update(mode)
    }

    fun setAmplitude(amplitude: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setAmplitude(amplitude) }
            return
        }
        orbView?.setAmplitude(amplitude)
    }

    fun hide(animated: Boolean = true, onHidden: (() -> Unit)? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide(animated, onHidden) }
            return
        }
        if (!visualAttached) {
            hiding = false
            onHidden?.invoke()
            return
        }
        if (hiding) {
            onHidden?.invoke()
            return
        }
        hiding = true
        touchOverlay?.remove()
        touchOverlay = null

        val orb = orbView
        val root = visualRoot
        if (!animated || orb == null || root == null) {
            removeVisualNow()
            hiding = false
            onHidden?.invoke()
            return
        }

        orb.animate().cancel()
        orb.animate()
            .alpha(0f)
            .translationY(JarvisOrbMetrics.dp(orb.context, 56f).toFloat())
            .setDuration(230L)
            .withEndAction {
                removeVisualNow()
                hiding = false
                onHidden?.invoke()
            }
            .start()
    }

    /** Remove immediately, for shutdown/cleanup paths. */
    fun hideNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideNow() }
            return
        }
        hiding = false
        removeVisualNow()
    }

    private fun removeVisualNow() {
        val wm = windowManager
        val root = visualRoot
        touchOverlay?.remove()
        touchOverlay = null
        visualAttached = false
        visualRoot = null
        orbView = null
        visualParams = null
        if (wm != null && root != null) {
            try {
                wm.removeView(root)
            } catch (t: Throwable) {
                Log.w(TAG, "removeView ignored: ${t.message}")
            }
        }
    }
}
