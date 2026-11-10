package app.screentextcopy

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * Fallback capture without the accessibility service.
 * Android asks for screen-capture consent each time; choose "Entire screen".
 */
class ProjectionActivity : Activity() {

    companion object {
        private const val REQUEST_CAPTURE = 7
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            finish()
            return
        }

        CaptureHolder.onProjectionResult = { bitmap ->
            CaptureHolder.onProjectionResult = null
            if (bitmap != null) {
                CaptureHolder.bitmap = bitmap
                startActivity(Intent(this, CaptureActivity::class.java))
            } else {
                Toast.makeText(this, "Screen capture failed", Toast.LENGTH_SHORT).show()
            }
            finish()
            overridePendingTransition(0, 0)
        }

        val service = Intent(this, ProjectionCaptureService::class.java)
            .putExtra(ProjectionCaptureService.EXTRA_CODE, resultCode)
            .putExtra(ProjectionCaptureService.EXTRA_DATA, data)
        startForegroundService(service)
    }
}
