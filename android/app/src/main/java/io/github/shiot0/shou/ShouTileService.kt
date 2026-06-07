package io.github.shiot0.shou

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.Executors

/**
 * Quick Settings tile: a one-swipe Shou control from anywhere. Tap toggles play/pause
 * when something's on; otherwise it wakes the PC and opens the remote. The tile label
 * tracks the active server and its state.
 */
class ShouTileService : TileService() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onStartListening() {
        super.onStartListening()
        ShouStore.init(this)
        renderTile()
    }

    override fun onClick() {
        super.onClick()
        ShouStore.init(this)
        val p = ShouStore.playback(this)
        if (p != null && p.active) {
            io.execute { ServerClient.command(applicationContext, "pause") }
            renderTile()
        } else {
            val mac = ShouStore.activeMac(this)
            if (mac.isNotBlank()) io.execute { Wol.wake(mac) }
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun renderTile() {
        val tile = qsTile ?: return
        val p = ShouStore.playback(this)
        val name = ShouStore.activeName(this)
        tile.label = "Shou"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                p != null && p.active && p.playing -> name + " · playing"
                p != null && p.active -> name + " · paused"
                else -> name
            }
        }
        tile.state = if (p != null && p.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_shou)
        tile.updateTile()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        /** Nudge the system to refresh the tile if it's being listened to. */
        fun refresh(ctx: Context) {
            runCatching {
                requestListeningState(
                    ctx.applicationContext,
                    ComponentName(ctx.applicationContext, ShouTileService::class.java),
                )
            }
        }
    }
}
