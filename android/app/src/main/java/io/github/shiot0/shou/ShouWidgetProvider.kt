package io.github.shiot0.shou

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: a compact "now playing" card — cover art, title, a live progress
 * bar, a status pill, and a full transport (wake · prev · −30s · play/pause · +30s · next)
 * — so it works as a real couch remote without ever opening the app. Cover art is fetched
 * asynchronously and pushed in a second pass once it's decoded.
 */
class ShouWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ShouStore.init(ctx)
        refresh(ctx)
    }

    companion object {
        // Widget-cover target size (px) — a rounded square thumbnail.
        private const val COVER_W = 132
        private const val COVER_H = 132

        private fun broadcast(ctx: Context, action: String, rc: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, rc, Intent(ctx, ActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun fmt(ms: Long): String {
            if (ms <= 0) return "0:00"
            val s = (ms / 1000).toInt()
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }

        private fun build(ctx: Context): RemoteViews {
            val app = ctx.applicationContext
            val v = RemoteViews(app.packageName, R.layout.widget_shou)
            val p = ShouStore.playback(app)
            val active = p != null && p.active

            if (active) {
                v.setTextViewText(R.id.w_title, p!!.title.ifBlank { "Now playing" })
                v.setImageViewResource(
                    R.id.w_play,
                    if (p.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                )
                v.setTextViewText(R.id.w_status, if (p.playing) "LIVE" else "PAUSED")
                v.setTextColor(R.id.w_status, if (p.playing) 0xFF5AD6A0.toInt() else 0xFFFF6A4D.toInt())
                v.setTextViewText(R.id.w_pos, fmt(p.positionMs))
                v.setTextViewText(R.id.w_dur, fmt(p.durationMs))
                val pct = if (p.durationMs > 0)
                    ((p.positionMs.toDouble() / p.durationMs) * 1000).toInt().coerceIn(0, 1000) else 0
                v.setProgressBar(R.id.w_bar, 1000, pct, false)
                // Cover art if we have it cached; otherwise the placeholder (a second
                // refresh pass below swaps in the real art once it's decoded).
                val art = ArtLoader.cached(p.cover)
                if (art != null) {
                    v.setImageViewBitmap(R.id.w_cover, ArtLoader.roundedCrop(art, COVER_W, COVER_H, 18f))
                } else {
                    v.setImageViewResource(R.id.w_cover, R.drawable.widget_cover_placeholder)
                }
            } else {
                v.setTextViewText(R.id.w_title, "Nothing playing")
                v.setImageViewResource(R.id.w_play, R.drawable.ic_media_play)
                v.setTextViewText(R.id.w_status, "IDLE")
                v.setTextColor(R.id.w_status, 0xFF9A94A6.toInt())
                v.setTextViewText(R.id.w_pos, "0:00")
                v.setTextViewText(R.id.w_dur, "0:00")
                v.setProgressBar(R.id.w_bar, 1000, 0, false)
                v.setImageViewResource(R.id.w_cover, R.drawable.widget_cover_placeholder)
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

            // If a cover is playing but not yet cached, fetch it and push a fresh render
            // with the artwork in place.
            val p = ShouStore.playback(app)
            if (p != null && p.active && p.cover.isNotBlank() && ArtLoader.cached(p.cover) == null) {
                ArtLoader.load(p.cover) {
                    val now = ShouStore.playback(app)
                    if (now != null && now.active && now.cover == p.cover) {
                        val refreshed = build(app)
                        for (id in mgr.getAppWidgetIds(ComponentName(app, ShouWidgetProvider::class.java))) {
                            mgr.updateAppWidget(id, refreshed)
                        }
                    }
                }
            }
        }
    }
}
