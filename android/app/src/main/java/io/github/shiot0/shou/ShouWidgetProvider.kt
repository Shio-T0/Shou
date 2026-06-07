package io.github.shiot0.shou

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: the active server's name, what's playing, and one-tap Wake /
 * prev / play-pause / next — a real couch remote you never have to open the app for.
 * Tapping the artwork opens the full remote.
 */
class ShouWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ShouStore.init(ctx)
        for (id in ids) mgr.updateAppWidget(id, build(ctx))
    }

    companion object {
        private fun broadcast(ctx: Context, action: String, rc: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, rc, Intent(ctx, ActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun build(ctx: Context): RemoteViews {
            val app = ctx.applicationContext
            val v = RemoteViews(app.packageName, R.layout.widget_shou)
            val p = ShouStore.playback(app)
            val name = ShouStore.activeName(app)

            v.setTextViewText(R.id.w_name, name)
            if (p != null && p.active) {
                v.setTextViewText(R.id.w_title, p.title.ifBlank { "Now playing" })
                v.setTextViewText(R.id.w_sub, p.subtitle.ifBlank { "Playing" })
                v.setImageViewResource(
                    R.id.w_play,
                    if (p.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                )
            } else {
                v.setTextViewText(R.id.w_title, "Nothing playing")
                v.setTextViewText(R.id.w_sub, "Tap to open Shou")
                v.setImageViewResource(R.id.w_play, R.drawable.ic_media_play)
            }

            v.setOnClickPendingIntent(R.id.w_wake, broadcast(app, ActionReceiver.ACTION_WAKE, 10))
            v.setOnClickPendingIntent(R.id.w_prev, broadcast(app, ActionReceiver.ACTION_PREV, 11))
            v.setOnClickPendingIntent(R.id.w_play, broadcast(app, ActionReceiver.ACTION_PAUSE, 12))
            v.setOnClickPendingIntent(R.id.w_next, broadcast(app, ActionReceiver.ACTION_NEXT, 13))

            val open = PendingIntent.getActivity(
                app, 14,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_body, open)
            return v
        }

        /** Re-render every placed widget (called when state or the active server changes). */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, ShouWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = build(app)
            for (id in ids) mgr.updateAppWidget(id, views)
        }
    }
}
