package app.screentextcopy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.content.IntentCompat

/** Foreground service required by Android to capture one frame through screen projection. */
class ProjectionCaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 42
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var delivered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            deliver(null)
            return START_NOT_STICKY
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            manager.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            deliver(null)
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { if (!delivered) deliver(null) }
            }
        }, handler)

        // Short pause so the consent dialog is gone before the frame is taken.
        handler.postDelayed({ startCapture(mp) }, 400)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.projection_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_capture)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.projection_notification))
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun startCapture(mp: MediaProjection) {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val dpi = resources.displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ r ->
            if (delivered) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * width
                val padded = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                padded.copyPixelsFromBuffer(plane.buffer)
                if (padded.width == width) padded
                else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            } catch (e: Exception) {
                null
            } finally {
                image.close()
            }
            deliver(bitmap)
        }, handler)

        display = try {
            mp.createVirtualDisplay(
                "ScreenTextCopy", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler
            )
        } catch (e: Exception) {
            deliver(null)
            null
        }
    }

    private fun deliver(bitmap: Bitmap?) {
        if (delivered) return
        delivered = true
        CaptureHolder.onProjectionResult?.invoke(bitmap)
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
