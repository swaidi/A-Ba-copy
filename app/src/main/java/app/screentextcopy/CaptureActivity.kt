package app.screentextcopy

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
import android.text.TextUtils
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            onSelectionChanged = { has ->
                if (has) {
                    resetExpand()
                    showActionBar()
                } else {
                    actionBar.visibility = View.GONE
                }
            }
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

        // Floating actions placed right under (or above) the selection, with a soft animated glow.
        val bar = GlowBar(this).apply {
            gravity = Gravity.CENTER
            setPadding(glowSize + dp(6), glowSize + dp(2), glowSize + dp(6), glowSize + dp(2))
            visibility = View.GONE
        }
        bar.addView(actionButton("Copy", R.drawable.ic_action_copy) { copy() })
        bar.addView(actionButton("Share", R.drawable.ic_action_share) { share() })
        expandButton = actionButton("Sentence", R.drawable.ic_action_sentence) { expandSelection() }
        bar.addView(expandButton)
        bar.addView(actionButton("All", R.drawable.ic_action_select_all) { selectionView.selectAll() })
        // Tap runs the default (starred) shortcut; long-press chooses another one.
        shortcutButton = actionButton("Smart", R.drawable.ic_action_shortcut) { runDefaultShortcut() }
        shortcutButton.setOnLongClickListener {
            pickShortcut()
            true
        }
        bar.addView(shortcutButton)
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
            minWidth = dp(48)
            minimumWidth = dp(48)
            setPadding(dp(12), paddingTop, dp(12), paddingBottom)
            setOnClickListener { action() }
        }

    // ---------- Action buttons: icon with a one-line label, or icon only when space is tight ----------

    private val actionButtons = ArrayList<Button>()
    private var compactBar = false

    private fun actionButton(label: String, icon: Int, action: () -> Unit) =
        Button(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minWidth = dp(52)
            minimumWidth = dp(52)
            maxWidth = dp(104)
            compoundDrawablePadding = dp(2)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { action() }
            actionButtons.add(this)
            setAction(this, label, icon)
        }

    // ---------- Shortcut button ----------

    private lateinit var shortcutButton: Button

    /** Shows the default shortcut's name on the button, or hides it if there are none. */
    private fun refreshShortcutButton() {
        val s = ShortcutStore.defaultShortcut(this)
        if (s == null) {
            shortcutButton.visibility = View.GONE
            return
        }
        shortcutButton.visibility = View.VISIBLE
        val label = if (s.type == Shortcut.TYPE_SMART) "Smart" else s.name
        setAction(shortcutButton, label, R.drawable.ic_action_shortcut)
        shortcutButton.tooltipText = null // long-press opens the shortcut list instead
    }

    private fun runShortcut(s: Shortcut) {
        val text = selectionView.selectedText()
        if (text.isBlank()) return
        ShortcutRunner.run(this, s, text, done = { finish() }, other = { pickShortcut() })
    }

    private fun runDefaultShortcut() {
        val s = ShortcutStore.defaultShortcut(this) ?: return pickShortcut()
        runShortcut(s)
    }

    private fun pickShortcut() {
        val list = ShortcutStore.load(this)
        if (list.isEmpty()) {
            Toast.makeText(this, "Add shortcuts in Alef_B_scan settings", Toast.LENGTH_LONG).show()
            return
        }
        val def = ShortcutStore.defaultIndex(this, list.size)
        val names = list.mapIndexed { i, s -> if (i == def) "★  ${s.name}" else s.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Run shortcut")
            .setItems(names) { _, i -> runShortcut(list[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setAction(button: Button, label: String, icon: Int) {
        button.tag = label
        button.contentDescription = label
        button.tooltipText = label // long-press shows the name in icon-only mode
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0)
        button.text = if (compactBar) "" else label
    }

    private fun setCompact(compact: Boolean) {
        compactBar = compact
        for (b in actionButtons) {
            b.text = if (compact) "" else (b.tag as? String ?: "")
            b.minWidth = if (compact) dp(48) else dp(52)
            b.minimumWidth = b.minWidth
        }
    }

    // ---------- Step-by-step selection: word → sentence → paragraph ----------

    private lateinit var expandButton: Button
    private var expandLevel = 0 // 0 = as selected, 1 = sentence, 2 = paragraph

    private fun resetExpand() {
        expandLevel = 0
        setAction(expandButton, "Sentence", R.drawable.ic_action_sentence)
        expandButton.visibility = if (selectionView.isAllSelected()) View.GONE else View.VISIBLE
    }

    private fun expandSelection() {
        if (expandLevel == 0 && selectionView.expandToSentence()) {
            expandLevel = 1
            setAction(expandButton, "Paragraph", R.drawable.ic_action_paragraph)
        } else {
            // Already a full sentence (or second tap): go to the whole paragraph.
            selectionView.expandToParagraph()
            expandLevel = 2
            expandButton.visibility = View.GONE
        }
        showActionBar()
    }

    /** Shows the action bar just below the selection, or above it when there is no room. */
    private fun showActionBar() {
        val bounds = selectionView.selectionBounds() ?: return
        val bar = actionBar as GlowBar
        val screenW = selectionView.width.toFloat()
        val screenH = selectionView.height.toFloat()
        val margin = dp(12).toFloat()
        // The view includes a transparent glow area around the visible bar.
        val g = bar.glowSize.toFloat()

        refreshShortcutButton()

        // Use icons with labels when they fit; on narrow screens or large fonts, icons only.
        fun measureVisibleWidth(): Float {
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return bar.measuredWidth - 2 * g
        }
        setCompact(false)
        if (measureVisibleWidth() > screenW - 2 * margin) {
            setCompact(true)
            measureVisibleWidth()
        }
        val w = bar.measuredWidth.toFloat() - 2 * g
        val h = bar.measuredHeight.toFloat() - 2 * g

        var y = bounds.bottom + dp(10)
        if (y + h > screenH - dp(24)) y = bounds.top - h - dp(16)
        if (y < dp(96)) y = (screenH - h) / 2f // very tall selection: keep it on screen
        val maxX = (screenW - w - margin).coerceAtLeast(margin)
        val x = (bounds.centerX() - w / 2f).coerceIn(margin, maxX)

        bar.x = x - g
        bar.y = y - g
        bar.visibility = View.VISIBLE
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
