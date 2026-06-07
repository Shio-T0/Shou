package io.github.shiot0.shou

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import java.util.concurrent.Executors

/**
 * Drives the active server's media notification + lock-screen controls. The web remote
 * pushes the kiosk's playback state down through [ShouBridge.playback]; we reflect it as
 * a `MediaSessionCompat` with a MediaStyle notification, and route the transport buttons
 * (which work from the lock screen / quick-settings, even with the app closed) back to
 * the PC over the same token-gated control endpoints.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSessionCompat
    private val io = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        session = MediaSessionCompat(this, "ShouMedia").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = send("pause")           // mpv pause is a toggle
                override fun onPause() = send("pause")
                override fun onSkipToNext() = send("next")
                override fun onSkipToPrevious() = send("prev")
                override fun onFastForward() = send("fwd")
                override fun onRewind() = send("rew")
                override fun onStop() { stopShelf() }
            })
            isActive = true
        }
    }

    private fun send(path: String) {
        io.execute { ServerClient.command(applicationContext, path) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Media buttons (BT headset, lock screen) arrive here and map to the callback.
        MediaButtonReceiver.handleIntent(session, intent)

        when (intent?.action) {
            ACTION_STOP -> { stopShelf(); return START_NOT_STICKY }
            else -> {
                val p = ShouStore.playback(applicationContext)
                if (p == null || !p.active) { stopShelf(); return START_NOT_STICKY }
                render(p)
            }
        }
        return START_NOT_STICKY
    }

    private var artUrl: String? = null

    private fun render(p: Playback) {
        artUrl = p.cover.ifBlank { null }
        val art = ArtLoader.cached(p.cover)
        publish(p, art)
        // Cover not in cache yet — fetch it, then re-publish with the artwork so the
        // notification + lock screen show the episode's poster.
        if (art == null && !p.cover.isBlank()) {
            val want = p.cover
            ArtLoader.load(p.cover) { bmp ->
                val now = ShouStore.playback(applicationContext)
                if (now != null && now.active && now.cover == want && artUrl == want) {
                    publish(now, bmp)
                }
            }
        }
    }

    private fun publish(p: Playback, art: android.graphics.Bitmap?) {
        val name = ShouStore.activeName(applicationContext)
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, p.title.ifBlank { "Shou" })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, p.subtitle.ifBlank { name })
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, name)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, p.durationMs.coerceAtLeast(0))
                .apply { if (art != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art) }
                .build()
        )
        val state = if (p.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, p.positionMs.coerceAtLeast(0), 1f)
                .build()
        )

        val notif = buildNotification(p, state, art)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(p: Playback, state: Int, art: android.graphics.Bitmap?): Notification {
        val playing = state == PlaybackStateCompat.STATE_PLAYING
        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(icon: Int, title: String, key: Long) = NotificationCompat.Action(
            icon, title, MediaButtonReceiver.buildMediaButtonPendingIntent(this, key),
        )
        val sub = p.subtitle.ifBlank { ShouStore.activeName(applicationContext) }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_stat_shou)
            .setContentTitle(p.title.ifBlank { "Shou" })
            .setContentText(sub)
            .setSubText(ShouStore.activeName(applicationContext))
            .setLargeIcon(art)
            .setContentIntent(contentPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
            .setColorized(true)
            .setColor(0xFFFF4A32.toInt())
            // Expanded view: prev · −30s · play/pause · +30s · next.
            .addAction(action(R.drawable.ic_media_prev, "Previous", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(action(R.drawable.ic_media_rew, "Rewind", PlaybackStateCompat.ACTION_REWIND))
            .addAction(
                if (playing) action(R.drawable.ic_media_pause, "Pause", PlaybackStateCompat.ACTION_PLAY_PAUSE)
                else action(R.drawable.ic_media_play, "Play", PlaybackStateCompat.ACTION_PLAY_PAUSE)
            )
            .addAction(action(R.drawable.ic_media_ffwd, "Fast forward", PlaybackStateCompat.ACTION_FAST_FORWARD))
            .addAction(action(R.drawable.ic_media_next, "Next", PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 2, 4)  // prev · play/pause · next
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
                    )
            )
            .build()
    }

    private fun stopShelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        session.isActive = false
        session.release()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_STOP = "io.github.shiot0.shou.media.STOP"
    }
}

/** Starts/updates/stops [PlaybackService] in response to live state from the web remote. */
object PlaybackController {
    fun update(ctx: Context, p: Playback?) {
        val app = ctx.applicationContext
        if (p == null || !p.active) {
            runCatching {
                app.startService(Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))
            }
            return
        }
        // Started while the app is foreground (state only flows while the WebView is live),
        // so the foreground-service start is allowed; guard anyway.
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
        }
    }
}
