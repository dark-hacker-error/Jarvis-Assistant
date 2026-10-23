package com.jarvis.assistant.ui.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.jarvis.assistant.R

/**
 * Custom liquid glass contour line that flows across the bottom navigation dock,
 * curving smoothly upwards into an arch over the center hero mic button,
 * and then returning to a horizontal straight line.
 */
class CurvedDockLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePath = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(4.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var accentColor: Int = Color.parseColor("#4FE3FF")

    init {
        resolveThemeColor()
    }

    fun resolveThemeColor() {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(R.attr.themeAccent, typedValue, true)
        accentColor = if (typedValue.data != 0) typedValue.data else Color.parseColor("#4FE3FF")
        updateShaders(width, height)
        invalidate()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        buildPath(w.toFloat(), h.toFloat())
        updateShaders(w, h)
    }

    private fun updateShaders(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val wFloat = w.toFloat()

        // Specular multi-stop linear gradient: vibrant accent with pure white specular highlight over the arch
        val lineColors = intArrayOf(
            Color.TRANSPARENT,
            Color.argb(50, 255, 255, 255),
            Color.argb(160, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Color.argb(250, 255, 255, 255), // peak specular white highlight right over the mic
            Color.argb(160, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Color.argb(50, 255, 255, 255),
            Color.TRANSPARENT
        )
        val positions = floatArrayOf(0.0f, 0.15f, 0.40f, 0.50f, 0.60f, 0.85f, 1.0f)
        linePaint.shader = LinearGradient(0f, 0f, wFloat, 0f, lineColors, positions, Shader.TileMode.CLAMP)

        // Soft ambient neon glow shader
        val glowColors = intArrayOf(
            Color.TRANSPARENT,
            Color.argb(15, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Color.argb(80, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Color.argb(140, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Color.argb(80, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Color.argb(15, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Color.TRANSPARENT
        )
        glowPaint.shader = LinearGradient(0f, 0f, wFloat, 0f, glowColors, positions, Shader.TileMode.CLAMP)
    }

    private fun buildPath(w: Float, h: Float) {
        linePath.reset()
        val centerX = w / 2f

        // Baseline sits along the top edge of the navigation dock
        val baselineY = h - dpToPx(3f)
        val archHeight = dpToPx(7.5f) // Climbs 7.5dp above the baseline, hugging the mic top
        val archRadius = dpToPx(29f)  // Hugs the 56dp circular mic button (r = 28dp)
        val spread = dpToPx(12f)      // Smooth organic easing into the horizontal line
        val marginStart = dpToPx(14f)

        // Left straight horizontal segment
        val archStartX = centerX - archRadius - spread
        linePath.moveTo(marginStart, baselineY)
        linePath.lineTo(archStartX, baselineY)

        // Smooth curve UP and OVER the mic button
        linePath.cubicTo(
            centerX - archRadius, baselineY,
            centerX - archRadius * 0.52f, baselineY - archHeight,
            centerX, baselineY - archHeight
        )

        // Smooth curve DOWN and back to the normal baseline
        val archEndX = centerX + archRadius + spread
        linePath.cubicTo(
            centerX + archRadius * 0.52f, baselineY - archHeight,
            centerX + archRadius, baselineY,
            archEndX, baselineY
        )

        // Right straight horizontal segment
        linePath.lineTo(w - marginStart, baselineY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!linePath.isEmpty) {
            // Draw soft liquid glass glow underneath
            canvas.drawPath(linePath, glowPaint)
            // Draw crisp specular contour line on top
            canvas.drawPath(linePath, linePaint)
        }
    }
}
