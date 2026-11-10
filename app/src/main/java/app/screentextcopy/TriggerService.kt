package app.screentextcopy

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.abs

/**
 * Accessibility service that draws a thin strip near the bottom edge of the screen.
 * A long-press on the strip captures the screen and opens the text selection screen.
 */
class TriggerService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        @Volatile
        var instance: TriggerService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var strip: View? = null
    private var lastCaptureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)
        refreshStrip()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: the service only provides the trigger strip and screenshots.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        removeStrip()
        instance = null
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        handler.post { refreshStrip() }
    }

    // ---------- Trigger strip ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun refreshStrip() {
        removeStrip()
        if (!Prefs.stripEnabled(this)) return
        addStrip()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addStrip() {
        val wm = windowManager ?: return
        val view = View(this)
        if (Prefs.stripVisible(this)) {
            view.setBackgroundColor(Color.argb(110, 72, 108, 184))
        } else {
            view.setBackgroundColor(Color.TRANSPARENT)
        }

        val screenWidth = wm.currentWindowMetrics.bounds.width()
        val params = WindowManager.LayoutParams(
            screenWidth * Prefs.stripWidthPct(this) / 100,
            dp(Prefs.stripHeightDp(this)),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(Prefs.stripOffsetDp(this@TriggerService))
            title = "ScreenTextCopyStrip"
        }

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        val longPress = Runnable {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            captureNow()
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    handler.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) {
                        handler.removeCallbacks(longPress)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                }
            }
            true
        }

        try {
            wm.addView(view, params)
            strip = view
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show the trigger strip: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeStrip() {
        val view = strip ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        strip = null
    }

    // ---------- Capture ----------

    /** Closes the notification shade first, then captures (used by the Quick Settings tile). */
    fun captureAfterClosingShade() {
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        handler.postDelayed({ captureNow() }, 650)
    }

    fun captureNow() {
        // Android allows roughly one accessibility screenshot per second.
        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < 1100) return
        lastCaptureAt = now

        strip?.visibility = View.INVISIBLE
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    strip?.visibility = View.VISIBLE
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    if (soft == null) {
                        Toast.makeText(this@TriggerService, "Screenshot failed", Toast.LENGTH_SHORT).show()
                        return
                    }
                    openCapture(soft)
                }

                override fun onFailure(errorCode: Int) {
                    strip?.visibility = View.VISIBLE
                    Toast.makeText(
                        this@TriggerService,
                        "Screenshot not allowed here (code $errorCode)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun openCapture(bitmap: Bitmap) {
        CaptureHolder.bitmap = bitmap
        val intent = Intent(this, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }
}
