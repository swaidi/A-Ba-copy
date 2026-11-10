package app.screentextcopy

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap

/** User settings stored on the device. */
object Prefs {
    private const val FILE = "settings"

    const val KEY_STRIP_ENABLED = "strip_enabled"
    const val KEY_STRIP_VISIBLE = "strip_visible"
    const val KEY_STRIP_HEIGHT_DP = "strip_height_dp"
    const val KEY_STRIP_OFFSET_DP = "strip_offset_dp"
    const val KEY_STRIP_WIDTH_PCT = "strip_width_pct"
    const val KEY_ARABIC = "ocr_arabic"

    const val DEFAULT_HEIGHT_DP = 16
    const val DEFAULT_OFFSET_DP = 26
    const val DEFAULT_WIDTH_PCT = 60

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun stripEnabled(c: Context) = get(c).getBoolean(KEY_STRIP_ENABLED, true)
    fun stripVisible(c: Context) = get(c).getBoolean(KEY_STRIP_VISIBLE, false)
    fun stripHeightDp(c: Context) = get(c).getInt(KEY_STRIP_HEIGHT_DP, DEFAULT_HEIGHT_DP)
    fun stripOffsetDp(c: Context) = get(c).getInt(KEY_STRIP_OFFSET_DP, DEFAULT_OFFSET_DP)
    fun stripWidthPct(c: Context) = get(c).getInt(KEY_STRIP_WIDTH_PCT, DEFAULT_WIDTH_PCT)
    fun arabic(c: Context) = get(c).getBoolean(KEY_ARABIC, true)

    fun ocrLanguages(c: Context) = if (arabic(c)) "ara+eng" else "eng"

    /** Shows or hides a Quick Settings tile by enabling or disabling its component. */
    fun setComponentEnabled(c: Context, cls: Class<*>, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        c.packageManager.setComponentEnabledSetting(
            ComponentName(c, cls), state, PackageManager.DONT_KILL_APP
        )
    }

    fun isComponentEnabled(c: Context, cls: Class<*>): Boolean {
        val state = c.packageManager.getComponentEnabledSetting(ComponentName(c, cls))
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}

/** Holds the latest screenshot in memory; bitmaps are too large to pass in an Intent. */
object CaptureHolder {
    @Volatile
    var bitmap: Bitmap? = null

    /** Called by the projection service once a frame is ready (main thread). */
    var onProjectionResult: ((Bitmap?) -> Unit)? = null
}
