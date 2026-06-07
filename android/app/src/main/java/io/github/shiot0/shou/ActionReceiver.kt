package io.github.shiot0.shou

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the lightweight actions fired by the home-screen widget and Quick Settings
 * tile — wake the PC, toggle play/pause, skip episodes — without opening the app.
 * Network work is done on a worker thread via goAsync() so the broadcast returns fast.
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        ShouStore.init(app)
        val action = intent.action ?: return
        val pending = goAsync()
        Thread {
            try {
                when (action) {
                    ACTION_WAKE -> {
                        val mac = ShouStore.activeMac(app)
                        if (mac.isNotBlank()) Wol.wake(mac)
                    }
                    ACTION_PAUSE -> ServerClient.command(app, "pause")
                    ACTION_NEXT -> ServerClient.command(app, "next")
                    ACTION_PREV -> ServerClient.command(app, "prev")
                    ACTION_REW -> ServerClient.command(app, "rew")
                    ACTION_FFWD -> ServerClient.command(app, "fwd")
                    ACTION_OPEN -> ServerClient.command(app, "open")
                }
            } finally {
                ShouWidgetProvider.refresh(app)
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_WAKE = "io.github.shiot0.shou.action.WAKE"
        const val ACTION_PAUSE = "io.github.shiot0.shou.action.PAUSE"
        const val ACTION_NEXT = "io.github.shiot0.shou.action.NEXT"
        const val ACTION_PREV = "io.github.shiot0.shou.action.PREV"
        const val ACTION_REW = "io.github.shiot0.shou.action.REW"
        const val ACTION_FFWD = "io.github.shiot0.shou.action.FFWD"
        const val ACTION_OPEN = "io.github.shiot0.shou.action.OPEN"
    }
}
