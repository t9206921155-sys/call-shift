package fi.callshift.app.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R

/** Плитка в шторке «Автоответчик»: одним нажатием включить/выключить. */
class AutoReplyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val settings = CallShiftApp.from(this).settings
        val cur = settings.autoReply
        val active = cur.isActiveAt(System.currentTimeMillis())
        // При включении из шторки — без срока, текст прежний.
        settings.setAutoReply(if (active) cur.copy(enabled = false) else cur.copy(enabled = true, untilMs = null))
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val s = CallShiftApp.from(this).settings.autoReply
        val on = s.isActiveAt(System.currentTimeMillis())
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Автоответчик"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !on -> "Выкл."
                s.untilMs != null -> "до " + AutoReplyActivity.fmt(s.untilMs).substringAfter(' ')
                else -> "Вкл."
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_autoreply)
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, AutoReplyTileService::class.java)) }
        }
    }
}
