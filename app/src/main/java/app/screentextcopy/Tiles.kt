package app.screentextcopy

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Opens an activity from a tile and collapses the shade (API 34+ requires a PendingIntent). */
@SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.launch(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pending = PendingIntent.getActivity(
            this, intent.component?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pending)
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}

/**
 * Quick toggle: turns the bottom-edge trigger service off in one tap
 * (for example before opening a banking app). Turning it back on opens
 * Accessibility settings, because Android only lets the user enable it there.
 */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val service = TriggerService.instance
        if (service != null) {
            service.disableSelf()
            qsTile?.let {
                it.state = Tile.STATE_INACTIVE
                it.updateTile()
            }
        } else {
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = TriggerService.instance != null
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "On" else "Off"
        }
        tile.updateTile()
    }
}

/**
 * Capture tile (fallback trigger). Uses the accessibility service when it is on;
 * otherwise uses screen capture, which works without the accessibility service.
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val service = TriggerService.instance
        if (service != null) {
            service.captureAfterClosingShade()
        } else {
            launch(Intent(this, ProjectionActivity::class.java))
        }
    }
}
