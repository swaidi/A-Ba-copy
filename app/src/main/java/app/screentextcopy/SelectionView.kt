package app.screentextcopy

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the frozen screen and lets the user select words:
 * - tap a word to select it, then drag the two handles to extend the selection;
 * - or draw a circle/scribble over text to select every word it touches.
 * All word boxes are in screenshot pixels; the view scales them to its size.
 */
class SelectionView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Called whenever the selection appears, changes or is cleared. */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    private var words: List<Word> = emptyList()
    private var selStart = -1
    private var selEnd = -1

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 120, 160, 240) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 72, 108, 184) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(72, 108, 184) }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.WHITE
    }
    private val lassoGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(110, 72, 108, 184)
    }
    private val lassoCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val lasso = Path()
    private val lassoPoints = ArrayList<PointF>()

    private var edgeAlpha = 0f
    private val edgeAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
        duration = 1100
        addUpdateListener {
            edgeAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    // ---------- Public API ----------

    fun setWords(list: List<Word>) {
        val keepStart = words.getOrNull(selStart)
        val keepEnd = words.getOrNull(selEnd)
        words = Word.order(list)
        selStart = if (keepStart != null) words.indexOf(keepStart) else -1
        selEnd = if (keepEnd != null) words.indexOf(keepEnd) else -1
        if (selStart < 0 || selEnd < 0) {
            selStart = -1
            selEnd = -1
        }
        invalidate()
    }

    fun hasWords() = words.isNotEmpty()

    fun hasSelection() = selStart >= 0 && selEnd >= 0

    fun selectAll() {
        if (words.isEmpty()) return
        selStart = 0
        selEnd = words.size - 1
        invalidate()
        onSelectionChanged?.invoke(true)
    }

    fun clearSelection() {
        selStart = -1
        selEnd = -1
        invalidate()
        onSelectionChanged?.invoke(false)
    }

    fun isAllSelected() = words.isNotEmpty() && selStart == 0 && selEnd == words.size - 1

    /**
     * Grows the selection to the whole sentence(s) it touches, staying inside the paragraph.
     * Returns false when the selection was already a full sentence.
     */
    fun expandToSentence(): Boolean {
        if (!hasSelection()) return false
        var s = selStart
        while (s > 0 && words[s - 1].para == words[s].para && !words[s - 1].endsSentence) s--
        var e = selEnd
        while (!words[e].endsSentence && e + 1 < words.size && words[e + 1].para == words[e].para) e++
        val changed = s != selStart || e != selEnd
        selStart = s
        selEnd = e
        invalidate()
        return changed
    }

    /** Grows the selection to the whole paragraph(s) it touches. */
    fun expandToParagraph(): Boolean {
        if (!hasSelection()) return false
        val firstPara = words[selStart].para
        val lastPara = words[selEnd].para
        var s = selStart
        while (s > 0 && words[s - 1].para == firstPara) s--
        var e = selEnd
        while (e + 1 < words.size && words[e + 1].para == lastPara) e++
        val changed = s != selStart || e != selEnd
        selStart = s
        selEnd = e
        invalidate()
        return changed
    }

    /** Bounds of the selected words in view coordinates, including the handles. */
    fun selectionBounds(): RectF? {
        if (!hasSelection()) return null
        val r = RectF(words[selStart].box)
        for (i in selStart..selEnd) r.union(words[i].box)
        return RectF(
            r.left * scaleX(), r.top * scaleY(),
            r.right * scaleX(), r.bottom * scaleY() + 26 * density
        )
    }

    fun selectedText(): String {
        if (!hasSelection()) return ""
        return Word.join(words.subList(selStart, selEnd + 1))
    }

    fun playEdgeGlow() {
        edgeAnimator.cancel()
        edgeAnimator.start()
    }

    // ---------- Coordinates ----------

    private fun scaleX(): Float = bitmap?.let { width / it.width.toFloat() } ?: 1f
    private fun scaleY(): Float = bitmap?.let { height / it.height.toFloat() } ?: 1f

    private fun toBitmap(x: Float, y: Float) = PointF(x / scaleX(), y / scaleY())

    // ---------- Drawing ----------

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, null, dst, bitmapPaint)

        canvas.save()
        canvas.scale(scaleX(), scaleY())
        val radius = 4 * density
        for ((i, w) in words.withIndex()) {
            val selected = hasSelection() && i in selStart..selEnd
            val r = RectF(w.box).apply { inset(-2 * density, -1 * density) }
            canvas.drawRoundRect(r, radius, radius, if (selected) selectedPaint else wordPaint)
        }
        canvas.restore()

        if (hasSelection()) {
            drawHandle(canvas, startAnchor(), startHandle())
            drawHandle(canvas, endAnchor(), endHandle())
        }

        if (!lasso.isEmpty) {
            canvas.drawPath(lasso, lassoGlow)
            canvas.drawPath(lasso, lassoCore)
        }

        if (edgeAlpha > 0f) drawEdgeGlow(canvas)
    }

    /** Anchor points in view coordinates: the outer corners of the first and last selected word. */
    private fun startAnchor(): PointF {
        val w = words[selStart]
        val x = if (w.rtl) w.box.right else w.box.left
        return PointF(x * scaleX(), w.box.bottom * scaleY())
    }

    private fun endAnchor(): PointF {
        val w = words[selEnd]
        val x = if (w.rtl) w.box.left else w.box.right
        return PointF(x * scaleX(), w.box.bottom * scaleY())
    }

    private val handleRadius get() = 11 * density

    /**
     * Handle positions lean outward from the selection (left for the start, right for the end;
     * mirrored for Arabic), so the two handles never cover each other on a short word.
     */
    private fun startHandle(): PointF {
        val a = startAnchor()
        val outward = if (words[selStart].rtl) 1f else -1f
        return PointF(a.x + outward * handleRadius * 0.9f, a.y + 13 * density)
    }

    private fun endHandle(): PointF {
        val a = endAnchor()
        val outward = if (words[selEnd].rtl) -1f else 1f
        return PointF(a.x + outward * handleRadius * 0.9f, a.y + 13 * density)
    }

    private fun drawHandle(canvas: Canvas, anchor: PointF, c: PointF) {
        handlePaint.strokeWidth = 3 * density
        canvas.drawLine(anchor.x, anchor.y - 2 * density, c.x, c.y, handlePaint)
        canvas.drawCircle(c.x, c.y, handleRadius, handlePaint)
        canvas.drawCircle(c.x, c.y, handleRadius, handleRing)
    }

    private fun drawEdgeGlow(canvas: Canvas) {
        val radius = 30 * density
        val layers = 6
        for (i in 0 until layers) {
            val inset = (i * 4 + 2) * density
            edgePaint.strokeWidth = 4 * density
            val alpha = (edgeAlpha * 210 * (1f - i.toFloat() / layers)).toInt().coerceIn(0, 255)
            edgePaint.color = Color.argb(alpha, 120, 160, 240)
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, edgePaint)
        }
    }

    // ---------- Touch ----------

    private enum class Mode { NONE, PENDING, LASSO, DRAG_START, DRAG_END }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                mode = Mode.PENDING
                if (hasSelection()) {
                    val grab = 30 * density
                    val s = startHandle()
                    val e = endHandle()
                    val ds = hypot(x - s.x, y - s.y)
                    val de = hypot(x - e.x, y - e.y)
                    if (ds < grab || de < grab) {
                        mode = if (ds <= de) Mode.DRAG_START else Mode.DRAG_END
                        onSelectionChanged?.invoke(false) // hide the action bar while dragging
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAG_START, Mode.DRAG_END -> dragHandle(x, y)
                Mode.PENDING -> if (hypot(x - downX, y - downY) > slop) {
                    mode = Mode.LASSO
                    onSelectionChanged?.invoke(false)
                    lasso.reset()
                    lassoPoints.clear()
                    lasso.moveTo(downX, downY)
                    lassoPoints.add(PointF(downX, downY))
                    addLassoPoint(x, y)
                }
                Mode.LASSO -> {
                    for (i in 0 until event.historySize) {
                        addLassoPoint(event.getHistoricalX(i), event.getHistoricalY(i))
                    }
                    addLassoPoint(x, y)
                }
                Mode.NONE -> {}
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.PENDING -> tapAt(x, y)
                    Mode.LASSO -> finishLasso()
                    Mode.DRAG_START, Mode.DRAG_END -> onSelectionChanged?.invoke(true)
                    Mode.NONE -> {}
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                lasso.reset()
                mode = Mode.NONE
            }
        }
        invalidate()
        return true
    }

    private fun addLassoPoint(x: Float, y: Float) {
        lasso.lineTo(x, y)
        lassoPoints.add(PointF(x, y))
    }

    private fun tapAt(x: Float, y: Float) {
        val p = toBitmap(x, y)
        val pad = 8 * density / scaleX()
        val hit = words.indexOfFirst {
            p.x >= it.box.left - pad && p.x <= it.box.right + pad &&
                p.y >= it.box.top - pad && p.y <= it.box.bottom + pad
        }
        if (hit >= 0) {
            selStart = hit
            selEnd = hit
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onSelectionChanged?.invoke(true)
        } else if (hasSelection()) {
            clearSelection()
        }
    }

    private fun dragHandle(x: Float, y: Float) {
        // Aim slightly above the finger so the word being chosen stays visible.
        val p = toBitmap(x, y - 28 * density)
        val nearest = nearestWord(p) ?: return
        if (mode == Mode.DRAG_START) {
            selStart = nearest
            if (selStart > selEnd) {
                selStart = selEnd.also { selEnd = selStart }
                mode = Mode.DRAG_END
            }
        } else {
            selEnd = nearest
            if (selEnd < selStart) {
                selStart = selEnd.also { selEnd = selStart }
                mode = Mode.DRAG_START
            }
        }
    }

    private fun nearestWord(p: PointF): Int? {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for ((i, w) in words.withIndex()) {
            val dx = max(0f, max(w.box.left - p.x, p.x - w.box.right))
            val dy = max(0f, max(w.box.top - p.y, p.y - w.box.bottom))
            val d = dx * dx + dy * dy * 4f // prefer words on the same line
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    /** Selects every word the drawn shape touches or encloses (whole words). */
    private fun finishLasso() {
        val sx = scaleX()
        val sy = scaleY()
        val closed = Path(lasso).apply { close() }
        val region = Region()
        region.setPath(closed, Region(0, 0, width, height))
        val touchPad = 10 * density

        var first = -1
        var last = -1
        for ((i, w) in words.withIndex()) {
            val v = Rect(
                (w.box.left * sx).toInt(), (w.box.top * sy).toInt(),
                (w.box.right * sx).toInt(), (w.box.bottom * sy).toInt()
            )
            var hit = !region.quickReject(v) && Region(v).op(region, Region.Op.INTERSECT)
            if (!hit) {
                for (pt in lassoPoints) {
                    if (pt.x >= v.left - touchPad && pt.x <= v.right + touchPad &&
                        pt.y >= v.top - touchPad && pt.y <= v.bottom + touchPad
                    ) {
                        hit = true
                        break
                    }
                }
            }
            if (hit) {
                if (first < 0) first = i
                last = i
            }
        }
        lasso.reset()
        lassoPoints.clear()
        if (first >= 0) {
            selStart = min(first, last)
            selEnd = max(first, last)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onSelectionChanged?.invoke(true)
        } else {
            onSelectionChanged?.invoke(hasSelection())
        }
    }
}
