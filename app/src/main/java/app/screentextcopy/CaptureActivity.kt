package app.screentextcopy

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

/**
 * Freezes the current screen and lets the user select words to copy.
 * Text read directly from apps is available immediately; text inside images
 * is recognized in the background and added when ready.
 */
class CaptureActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var selectionView: SelectionView
    private lateinit var statusText: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var actionBar: View
    private var screenshot: Bitmap? = null
    private var directWords: List<Word> = emptyList()
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitions()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        buildLayout()
        loadCapture()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadCapture()
    }

    private fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    // ---------- Loading ----------

    private fun loadCapture() {
        val bmp = CaptureHolder.bitmap
        CaptureHolder.bitmap = null
        if (bmp == null) {
            if (screenshot == null) finish()
            return
        }
        screenshot = bmp
        directWords = CaptureHolder.directWords
        CaptureHolder.directWords = emptyList()

        selectionView.bitmap = bmp
        selectionView.setWords(directWords)
        selectionView.clearSelection()
        selectionView.post { selectionView.playEdgeGlow() }
        recognizeImages(bmp)
    }

    private fun recognizeImages(bmp: Bitmap) {
        val gen = ++generation
        setStatus(
            if (directWords.isEmpty()) "Reading text…" else "Tap a word, or circle text  ·  reading images…",
            busy = true
        )
        val skip = directWords.map { it.box }
        worker.execute {
            val result = try {
                Result.success(OcrEngine.recognizeWords(this, bmp, skip))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                if (gen != generation || isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { ocr ->
                        val merged = directWords + ocr.filterNot { overlapsDirect(it.box) }
                        selectionView.setWords(merged)
                        if (merged.isEmpty()) {
                            setStatus("No text found on this screen", busy = false)
                        } else {
                            setStatus("Tap a word, or circle text", busy = false)
                        }
                    },
                    onFailure = {
                        setStatus("Image text could not be read: ${it.message}", busy = false)
                    }
                )
            }
        }
    }

    /** True when an OCR word mostly covers text that was already read directly. */
    private fun overlapsDirect(box: RectF): Boolean {
        val area = box.width() * box.height()
        if (area <= 0f) return true
        for (d in directWords) {
            val i = RectF()
            if (i.setIntersect(box, d.box) && i.width() * i.height() > area * 0.3f) return true
        }
        return false
    }

    // ---------- Layout ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(alpha: Int = 225) = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(Color.argb(alpha, 20, 28, 44))
    }

    private fun buildLayout() {
        val root = FrameLayout(this)

        selectionView = SelectionView(this).apply {
            onSelectionChanged = { has -> if (has) showActionBar() else actionBar.visibility = View.GONE }
        }
        root.addView(selectionView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Top: status and close.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill()
            setPadding(dp(14), dp(4), dp(4), dp(4))
        }
        statusSpinner = ProgressBar(this).apply { visibility = View.GONE }
        top.addView(statusSpinner, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        top.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(barButton("Close") { finish() })
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { setMargins(dp(16), dp(36), dp(16), 0) })

        // Floating actions placed right under (or above) the selection, with a glow.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = barBackground
            setPadding(dp(6), dp(2), dp(6), dp(2))
            elevation = dp(14).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineSpotShadowColor = Color.rgb(120, 170, 255)
                outlineAmbientShadowColor = Color.rgb(120, 170, 255)
            }
            visibility = View.GONE
        }
        bar.addView(barButton("Copy") { copy() })
        bar.addView(barButton("Share") { share() })
        bar.addView(barButton("Select all") { selectionView.selectAll() })
        actionBar = bar
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))

        setContentView(root)
    }

    private fun barButton(label: String, action: () -> Unit) =
        Button(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            setTextColor(Color.WHITE)
            isAllCaps = false
            minWidth = dp(64)
            setOnClickListener { action() }
        }

    private val barBackground by lazy {
        GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.argb(250, 72, 108, 184))
            setStroke(dp(2), Color.rgb(207, 224, 255))
        }
    }

    private var glowAnimator: ValueAnimator? = null

    /** Shows the action bar just below the selection, or above it when there is no room. */
    private fun showActionBar() {
        val bounds = selectionView.selectionBounds() ?: return
        val bar = actionBar
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = bar.measuredWidth.toFloat()
        val h = bar.measuredHeight.toFloat()
        val screenW = selectionView.width.toFloat()
        val screenH = selectionView.height.toFloat()
        val margin = dp(12).toFloat()

        var y = bounds.bottom + dp(8)
        if (y + h > screenH - dp(24)) y = bounds.top - h - dp(14)
        if (y < dp(96)) y = (screenH - h) / 2f // very tall selection: keep it on screen
        val maxX = (screenW - w - margin).coerceAtLeast(margin)
        val x = (bounds.centerX() - w / 2f).coerceIn(margin, maxX)

        bar.x = x
        bar.y = y
        bar.visibility = View.VISIBLE
        pulseGlow()
    }

    /** A short glowing pulse on the bar's border so it stands out on any background. */
    private fun pulseGlow() {
        glowAnimator?.cancel()
        glowAnimator = ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0.4f).apply {
            duration = 1400
            addUpdateListener {
                val t = it.animatedValue as Float
                val width = dp(2) + (dp(3) * t).toInt()
                val c = Color.rgb(207 + (48 * t).toInt(), 224 + (31 * t).toInt(), 255)
                barBackground.setStroke(width, c)
                actionBar.elevation = dp(10) + dp(14) * t
            }
            start()
        }
    }

    private fun setStatus(text: String, busy: Boolean) {
        statusText.text = text
        statusSpinner.visibility = if (busy) View.VISIBLE else View.GONE
    }

    // ---------- Actions ----------

    private fun copy() {
        val text = selectionView.selectedText()
        if (text.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Screen text", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun share() {
        val text = selectionView.selectedText()
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share text"))
        finish()
    }

    override fun onDestroy() {
        generation++
        OcrEngine.stop()
        worker.shutdownNow()
        super.onDestroy()
    }
}
