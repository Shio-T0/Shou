package io.github.shiot0.shou

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * System notifications and their channels. Two kinds beyond the media controls:
 * an episode finishing (pushed live from the web remote) and a Watching show getting a
 * new episode (found by [AiringWorker] polling your own server). Both fit Shou's
 * local, account-free model — no cloud push, just your PC and your phone.
 */
object Notifications {

    const val CHANNEL_PLAYBACK = "playback"
    const val CHANNEL_EVENTS = "episodes"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val playback = NotificationChannel(
            CHANNEL_PLAYBACK, "Playback controls", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Lock-screen controls for what's playing on the PC"
            setShowBadge(false)
        }
        val events = NotificationChannel(
            CHANNEL_EVENTS, "Episodes", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Finished episodes and newly-aired episodes of shows you're watching"
        }
        mgr.createNotificationChannel(playback)
        mgr.createNotificationChannel(events)
    }

    /** Post an event notification. [kind] tags the use ("finished" / "airing") and
     *  buckets the id so repeats of the same kind+title coalesce. */
    fun event(ctx: Context, kind: String, title: String, body: String) {
        ensureChannels(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_shou)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(0xFFFF4A32.toInt())
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()
        val id = (kind + "|" + title).hashCode()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}
