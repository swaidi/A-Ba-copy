package app.screentextcopy

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Shows the screenshot and lets the user circle (or scribble over) the text to copy.
 * The screen edges light up briefly when the view opens.
 */
class SelectionView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            path.reset()
            invalidate()
        }

    /** Called with the selected area in screenshot pixels. */
    var onSelected: ((Rect) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val path = Path()
    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(70, 0, 0, 0) }
    private val strokeGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(120, 72, 108, 184)
    }
    private val strokeCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var edgeAlpha = 0f
    private val edgeAnimator = ValueAnimator.ofFloat(0f, 1f, 0.35f).apply {
        duration = 900
        addUpdateListener {
            edgeAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    fun playEdgeGlow() {
        edgeAnimator.cancel()
        edgeAnimator.start()
    }

    fun clearSelection() {
        path.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        canvas.drawRect(dst, dimPaint)

        if (!path.isEmpty) {
            canvas.drawPath(path, strokeGlow)
            canvas.drawPath(path, strokeCore)
        }

        if (edgeAlpha > 0f) drawEdgeGlow(canvas)
    }

    /** Layered strokes around the screen border, similar to the stock edge light effect. */
    private fun drawEdgeGlow(canvas: Canvas) {
        val radius = 28 * density
        val layers = 5
        for (i in 0 until layers) {
            val inset = (i * 4 + 2) * density
            edgePaint.strokeWidth = 4 * density
            val alpha = (edgeAlpha * 200 * (1f - i.toFloat() / layers)).toInt().coerceIn(0, 255)
            edgePaint.color = Color.argb(alpha, 120, 160, 240)
            canvas.drawRoundRect(
                inset, inset, width - inset, height - inset, radius, radius, edgePaint
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                path.lineTo(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                path.lineTo(event.x, event.y)
                finishSelection()
            }
            MotionEvent.ACTION_CANCEL -> path.reset()
        }
        invalidate()
        return true
    }

    private fun finishSelection() {
        val bmp = bitmap ?: return
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val minSize = 24 * density
        if (bounds.width() < minSize && bounds.height() < minSize) {
            path.reset()
            return
        }
        // Map from view coordinates to screenshot pixels, with a small margin.
        val sx = bmp.width / width.toFloat()
        val sy = bmp.height / height.toFloat()
        val pad = 6 * density
        val rect = Rect(
            ((bounds.left - pad) * sx).toInt().coerceIn(0, bmp.width - 1),
            ((bounds.top - pad) * sy).toInt().coerceIn(0, bmp.height - 1),
            ((bounds.right + pad) * sx).toInt().coerceIn(1, bmp.width),
            ((bounds.bottom + pad) * sy).toInt().coerceIn(1, bmp.height)
        )
        if (rect.width() > 4 && rect.height() > 4) onSelected?.invoke(rect)
    }
}
