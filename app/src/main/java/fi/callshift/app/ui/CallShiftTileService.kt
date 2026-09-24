package fi.callshift.app.ui

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R

/**
 * Quick Settings Tile (ТЗ FR-8.3, M2).
 * Позволяет в 1 тап из шторки включить/выключить переадресацию (мастер-переключатель).
 */
@RequiresApi(Build.VERSION_CODES.N)
class CallShiftTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val app = CallShiftApp.from(this)
        val newState = !app.settings.masterEnabled
        app.settings.setMasterEnabled(newState)
        updateTileState()

        // Уведомление о смене состояния
        app.notifier.showStatus(
            active = newState,
            profile = app.profile.name,
            rulesCount = 0,
        )
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val app = CallShiftApp.from(this)
        val enabled = app.settings.masterEnabled

        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (enabled) R.string.tile_active else R.string.tile_inactive)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_forward)
        tile.updateTile()
    }
}
