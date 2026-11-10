package app.screentextcopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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

/** Shows the screenshot, lets the user circle text, then recognizes and copies it. */
class CaptureActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var selectionView: SelectionView
    private lateinit var topBar: View
    private lateinit var progress: View
    private var screenshot: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        buildLayout()
        loadScreenshot()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadScreenshot()
    }

    private fun loadScreenshot() {
        val bmp = CaptureHolder.bitmap
        CaptureHolder.bitmap = null
        if (bmp == null) {
            if (screenshot == null) finish()
            return
        }
        screenshot = bmp
        selectionView.bitmap = bmp
        selectionView.post { selectionView.playEdgeGlow() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildLayout() {
        val root = FrameLayout(this)

        selectionView = SelectionView(this).apply {
            onSelected = { rect -> recognize(rect) }
        }
        root.addView(selectionView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val pill = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.argb(220, 20, 28, 44))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill
            setPadding(dp(16), dp(6), dp(6), dp(6))
        }
        bar.addView(TextView(this).apply {
            text = "Circle the text to copy"
            setTextColor(Color.WHITE)
            textSize = 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(smallButton("All") { recognizeWholeScreen() })
        bar.addView(smallButton("Close") { finish() })
        topBar = bar
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply { setMargins(dp(16), dp(40), dp(16), 0) })

        val progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(235, 20, 28, 44))
            }
            setPadding(dp(20), dp(16), dp(24), dp(16))
            visibility = View.GONE
        }
        progressBox.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(28), dp(28)))
        progressBox.addView(TextView(this).apply {
            text = "Reading text…"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(14), 0, 0, 0)
        })
        progress = progressBox
        root.addView(progressBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        setContentView(root)
    }

    private fun smallButton(label: String, action: () -> Unit) =
        Button(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            setTextColor(Color.WHITE)
            isAllCaps = false
            minWidth = dp(56)
            setOnClickListener { action() }
        }

    private fun recognizeWholeScreen() {
        val bmp = screenshot ?: return
        recognize(Rect(0, 0, bmp.width, bmp.height))
    }

    private fun recognize(rect: Rect) {
        val bmp = screenshot ?: return
        setBusy(true)
        worker.execute {
            val result = try {
                val crop = Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
                val text = OcrEngine.recognize(this, crop)
                if (crop !== bmp) crop.recycle()
                Result.success(text)
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                result.fold(
                    onSuccess = { showResult(it) },
                    onFailure = {
                        Toast.makeText(this, "Text recognition failed: ${it.message}", Toast.LENGTH_LONG).show()
                        selectionView.clearSelection()
                    }
                )
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        topBar.visibility = if (busy) View.INVISIBLE else View.VISIBLE
        selectionView.isEnabled = !busy
    }

    private fun showResult(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "No text found. Try circling a larger area.", Toast.LENGTH_LONG).show()
            selectionView.clearSelection()
            return
        }
        val editor = EditText(this).apply {
            setText(text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            maxLines = 12
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Recognized text")
            .setView(editor)
            .setPositiveButton("Copy") { _, _ -> copy(editor.text.toString()) }
            .setNeutralButton("Share") { _, _ -> share(editor.text.toString()) }
            .setNegativeButton("Select again") { _, _ -> selectionView.clearSelection() }
            .setOnCancelListener { selectionView.clearSelection() }
            .show()
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Screen text", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share text"))
        finish()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
