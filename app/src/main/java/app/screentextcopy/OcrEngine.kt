package app.screentextcopy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** Offline text recognition with Tesseract (English and Arabic models bundled in the APK). */
object OcrEngine {
    private var api: TessBaseAPI? = null
    private var loadedLanguages: String? = null

    private fun dataDir(context: Context) = File(context.filesDir, "tesseract")

    /** Copies the model files from the APK to app storage (once per app version). */
    private fun ensureModels(context: Context) {
        val tessdata = File(dataDir(context), "tessdata")
        tessdata.mkdirs()
        val marker = File(tessdata, ".version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (marker.exists() && marker.readText() == version) return

        val names = context.assets.list("tessdata") ?: emptyArray()
        for (name in names) {
            if (!name.endsWith(".traineddata")) continue
            context.assets.open("tessdata/$name").use { input ->
                File(tessdata, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        marker.writeText(version)
    }

    @Synchronized
    fun recognize(context: Context, source: Bitmap): String {
        val app = context.applicationContext
        ensureModels(app)
        val languages = Prefs.ocrLanguages(app)

        var tess = api
        if (tess == null || loadedLanguages != languages) {
            tess?.recycle()
            tess = TessBaseAPI()
            if (!tess.init(dataDir(app).absolutePath, languages)) {
                tess.recycle()
                api = null
                loadedLanguages = null
                throw IllegalStateException("Text recognition models could not be loaded ($languages)")
            }
            api = tess
            loadedLanguages = languages
        }

        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        val prepared = prepare(source)
        tess.setImage(prepared)
        val text = tess.getUTF8Text() ?: ""
        tess.clear()
        if (prepared !== source) prepared.recycle()
        return cleanup(text)
    }

    /** Upscales small selections and converts to grayscale, which improves accuracy. */
    private fun prepare(src: Bitmap): Bitmap {
        val scale = when {
            src.height < 120 -> 3f
            src.height < 400 || src.width < 400 -> 2f
            else -> 1f
        }
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.scale(scale, scale)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun cleanup(text: String): String =
        text.lines()
            .map { it.trimEnd() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
