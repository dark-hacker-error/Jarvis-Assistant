package com.jarvis.assistant.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.sin

/**
 * Siri/Gemini style orb and working pill.
 *
 * The view intentionally remains a fixed 140dp x 76dp box. That gives the
 * bottom-center placement one stable anchor while the drawing inside animates
 * between:
 *   - a 75dp circular neon orb (listening)
 *   - a 140dp x 36dp glowing pill (agent working)
 *
 * This view is visual only. It is rendered inside the non-touchable system
 * session window, so it can never intercept a click intended for the app below.
 */
class JarvisOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { LISTENING, WORKING }

    companion object {
        private const val ORB_DIAMETER_DP = 75f
        private const val PILL_WIDTH_DP = 140f
        private const val PILL_HEIGHT_DP = 36f
        private const val WORKING_TEXT = "Jarvis is working..."
    }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.15f)
        color = Color.argb(190, 235, 250, 255)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(150, 235, 248, 255)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 9.5f * scaledDensity
    }

    private var mode = Mode.LISTENING
    private var modeProgress = 0f
    private var statusText = WORKING_TEXT

    private var targetAmplitude = 0f
    private var displayedAmplitude = 0f
    private var pulsePhase = 0f

    private var modeAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
    }

    fun setMode(nextMode: Mode, status: String? = null) {
        if (status != null && status != statusText) {
            statusText = status
            invalidate()
        }
        if (mode == nextMode) return
        mode = nextMode
        modeAnimator?.cancel()
        val target = if (nextMode == Mode.WORKING) 1f else 0f
        modeAnimator = ValueAnimator.ofFloat(modeProgress, target).apply {
            duration = 260L
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                modeProgress = (it.animatedValue as Float).coerceIn(0f, 1f)
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    fun setWorkingStatus(text: String?) {
        val safe = text?.trim().orEmpty()
        val next = if (safe.isBlank()) WORKING_TEXT else safe
        if (next != statusText) {
            statusText = next
            invalidate()
        }
    }

    fun setAmplitude(level: Float) {
        targetAmplitude = level.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pulseAnimator == null) {
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_500L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    pulsePhase = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
            }
        }
        pulseAnimator?.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        modeAnimator?.cancel()
        modeAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        displayedAmplitude += (targetAmplitude - displayedAmplitude) * 0.18f
        if (displayedAmplitude < 0.001f) displayedAmplitude = 0f

        val cx = width / 2f
        val cy = height / 2f
        val listeningAlpha = (1f - modeProgress).coerceIn(0f, 1f)
        val workingAlpha = modeProgress.coerceIn(0f, 1f)

        if (listeningAlpha > 0.01f) {
            drawOrb(canvas, cx, cy, listeningAlpha)
        }
        if (workingAlpha > 0.01f) {
            drawWorkingPill(canvas, cx, cy, workingAlpha)
        }
    }

    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        val baseRadius = dp(ORB_DIAMETER_DP / 2f)
        // 1.0f at silence, up to 1.35f at full microphone amplitude. A tiny
        // breath keeps the orb alive without changing the requested range.
        val breathing = 0.012f * (0.5f + 0.5f * sin(pulsePhase * Math.PI * 2).toFloat())
        val pulse = 1f + (0.35f * displayedAmplitude) + breathing
        val radius = baseRadius * pulse

        val glowRadius = radius * 2.05f
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb((135 * alpha).toInt(), 70, 224, 255),
                Color.argb((85 * alpha).toInt(), 62, 126, 255),
                Color.argb((55 * alpha).toInt(), 144, 65, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.38f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        orbPaint.shader = RadialGradient(
            cx - radius * 0.18f,
            cy - radius * 0.22f,
            radius * 1.22f,
            intArrayOf(
                Color.argb((255 * alpha).toInt(), 255, 255, 255),
                Color.argb((255 * alpha).toInt(), 132, 246, 255),
                Color.argb((250 * alpha).toInt(), 41, 151, 255),
                Color.argb((240 * alpha).toInt(), 86, 62, 255),
                Color.argb((120 * alpha).toInt(), 83, 28, 202)
            ),
            floatArrayOf(0f, 0.18f, 0.46f, 0.74f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, orbPaint)

        ringPaint.alpha = (190 * alpha).toInt()
        canvas.drawCircle(cx, cy, radius * 0.96f, ringPaint)

        highlightPaint.shader = RadialGradient(
            cx - radius * 0.3f,
            cy - radius * 0.34f,
            radius * 0.42f,
            intArrayOf(
                Color.argb((205 * alpha).toInt(), 255, 255, 255),
                Color.argb((70 * alpha).toInt(), 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx - radius * 0.16f, cy - radius * 0.2f, radius * 0.4f, highlightPaint)
    }

    private fun drawWorkingPill(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        val pillWidth = dp(PILL_WIDTH_DP)
        val pillHeight = dp(PILL_HEIGHT_DP)
        val left = cx - pillWidth / 2f
        val top = cy - pillHeight / 2f
        val right = cx + pillWidth / 2f
        val bottom = cy + pillHeight / 2f
        val rect = RectF(left, top, right, bottom)
        val radius = pillHeight / 2f

        pillPaint.shader = LinearGradient(
            left, top, right, bottom,
            intArrayOf(
                Color.argb((245 * alpha).toInt(), 30, 207, 255),
                Color.argb((248 * alpha).toInt(), 39, 112, 255),
                Color.argb((245 * alpha).toInt(), 132, 61, 255)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, pillPaint)

        pillBorderPaint.alpha = (150 * alpha).toInt()
        canvas.drawRoundRect(rect, radius, radius, pillBorderPaint)

        // Small live status dot on the left.
        val dotX = left + dp(13f)
        dotPaint.color = Color.argb((235 * alpha).toInt(), 231, 255, 255)
        canvas.drawCircle(dotX, cy, dp(3.2f), dotPaint)
        dotPaint.color = Color.argb((90 * alpha).toInt(), 255, 255, 255)
        canvas.drawCircle(dotX, cy, dp(6.2f), dotPaint)

        val availableWidth = pillWidth - dp(30f)
        val oldSize = textPaint.textSize
        var size = 9.5f * scaledDensity
        textPaint.textSize = size
        if (textPaint.measureText(statusText) > availableWidth) {
            size = (size * (availableWidth / textPaint.measureText(statusText))).coerceAtLeast(8f * scaledDensity)
            textPaint.textSize = size
        }
        textPaint.alpha = (255 * alpha).toInt()
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        val title = TextUtils.ellipsize(
            statusText,
            textPaint,
            availableWidth,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(title, cx + dp(6f), baseline, textPaint)
        textPaint.textSize = oldSize
    }

    private fun dp(value: Float): Float = value * density
}
