package app.screentextcopy

import android.animation.TimeAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.widget.LinearLayout
import kotlin.random.Random

/**
 * Action bar with a soft, faded glow around it (no solid border), in the same style
 * as the screen-edge glow. The bright spots of the glow drift around the bar in a
 * random, slowly changing motion, and the overall brightness breathes gently.
 * The glow is drawn inside the view's padding, so nothing is clipped.
 */
class GlowBar(context: Context) : LinearLayout(context) {

    private val d = resources.displayMetrics.density

    /** Space reserved around the bar for the glow. */
    val glowSize = (26 * d).toInt()

    private val cornerRadius = 24 * d
    private val layers = 5

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 72, 108, 184)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * d
        maskFilter = BlurMaskFilter(6 * d, BlurMaskFilter.Blur.NORMAL)
    }

    private val pill = RectF()
    private val ring = RectF()
    private val matrix1 = Matrix()
    private val matrix2 = Matrix()
    private var shader1: SweepGradient? = null
    private var shader2: SweepGradient? = null

    // Random-walk state for the two glow layers and the brightness.
    private val rnd = Random(System.nanoTime())
    private var angle1 = rnd.nextFloat() * 360f
    private var speed1 = 1.2f
    private var angle2 = angle1 + 180f
    private var speed2 = -0.9f
    private var intensity = 0f
    private var targetIntensity = 1f

    private val ticker = TimeAnimator().apply {
        setTimeListener { _, _, deltaMs -> step(deltaMs) }
    }

    init {
        setWillNotDraw(false)
        orientation = HORIZONTAL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val g = glowSize.toFloat()
        pill.set(g, g, w - g, h - g)
        val cx = w / 2f
        val cy = h / 2f
        // Colors with uneven transparency: the bright parts travel as the gradient turns.
        shader1 = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(255, 120, 160, 240),
                Color.argb(40, 120, 160, 240),
                Color.argb(230, 90, 200, 255),
                Color.argb(25, 120, 160, 240),
                Color.argb(255, 120, 160, 240)
            ),
            floatArrayOf(0f, 0.28f, 0.5f, 0.78f, 1f)
        )
        shader2 = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(0, 150, 130, 255),
                Color.argb(210, 150, 130, 255),
                Color.argb(0, 150, 130, 255),
                Color.argb(150, 110, 180, 255),
                Color.argb(0, 150, 130, 255)
            ),
            floatArrayOf(0f, 0.2f, 0.45f, 0.7f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (intensity > 0.01f) {
            drawGlowLayer(canvas, shader1, matrix1, angle1, 1f)
            drawGlowLayer(canvas, shader2, matrix2, angle2, 0.8f)
        }
        canvas.drawRoundRect(pill, cornerRadius, cornerRadius, fillPaint)
        super.onDraw(canvas)
    }

    /** Layered strokes fading outward, like the screen-edge glow. */
    private fun drawGlowLayer(canvas: Canvas, shader: SweepGradient?, m: Matrix, angle: Float, strength: Float) {
        shader ?: return
        m.setRotate(angle, width / 2f, height / 2f)
        shader.setLocalMatrix(m)
        glowPaint.shader = shader
        for (i in 0 until layers) {
            val out = (i * 3 + 1) * d
            ring.set(pill.left - out, pill.top - out, pill.right + out, pill.bottom + out)
            glowPaint.alpha = (intensity * strength * 220 * (1f - i.toFloat() / layers)).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(ring, cornerRadius + out, cornerRadius + out, glowPaint)
        }
    }

    /** Advances the random drift; speeds and brightness change slowly and unpredictably. */
    private fun step(deltaMs: Long) {
        val f = (deltaMs / 16f).coerceIn(0f, 4f)
        speed1 = (speed1 + (rnd.nextFloat() - 0.5f) * 0.5f * f).coerceIn(-3.5f, 3.5f)
        speed2 = (speed2 + (rnd.nextFloat() - 0.5f) * 0.5f * f).coerceIn(-3.5f, 3.5f)
        angle1 = (angle1 + speed1 * f) % 360f
        angle2 = (angle2 + speed2 * f) % 360f
        if (rnd.nextFloat() < 0.015f * f) targetIntensity = 0.55f + rnd.nextFloat() * 0.45f
        intensity += (targetIntensity - intensity) * 0.04f * f
        invalidate()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) {
            intensity = 0f // fade in each time the bar appears
            targetIntensity = 1f
            if (isAttachedToWindow && !ticker.isStarted) ticker.start()
        } else {
            ticker.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && !ticker.isStarted) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }
}
