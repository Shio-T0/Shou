package io.github.shiot0.shou

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.shiot0.shou.ui.Glyph
import io.github.shiot0.shou.ui.Pressable
import io.github.shiot0.shou.ui.PrimaryButton
import io.github.shiot0.shou.ui.Shu
import io.github.shiot0.shou.ui.ShouTheme
import io.github.shiot0.shou.ui.Type
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * "Watch on phone": the episode the PC was playing, continued here in a native player.
 * The server re-resolves a phone-playable stream and proxies it (adding the Referer the
 * CDN needs); we play it full screen in landscape from where the PC was. "Back to the PC"
 * (or the back gesture) tells the server where we stopped so mpv picks up from there.
 */
@OptIn(UnstableApi::class)
class CastActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private var loaded = ""
    private val cast = mutableStateOf<Cast?>(null)
    private val failed = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        LiveLink.init(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage("en")
                    .setSelectUndeterminedTextLanguage(true)
                    .build()
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        failed.value = "This source won't play on the phone."
                    }
                })
            }

        onBackPressedDispatcher.addCallback(this) { throwBack() }

        setContent {
            ShouTheme { CastScreen(player, cast.value, failed.value, ::throwBack) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveLink.state.filterNotNull().collect { onCast(it.cast) }
            }
        }
    }

    private fun onCast(c: Cast?) {
        if (c == null || !c.active) {
            if (!isFinishing) finish()
            return
        }
        cast.value = c
        if (c.error.isNotBlank()) {
            failed.value = c.error
            return
        }
        if (c.resolving || c.src.isBlank()) return
        val sig = c.kind + "|" + c.src
        if (sig == loaded) return
        loaded = sig
        failed.value = null
        val base = ShouStore.activeBaseUrl(this) ?: return
        val item = MediaItem.Builder().setUri(base + c.src)
        // The proxy URL has no file extension, so say what HLS is.
        if (c.kind == "hls") item.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (c.sub.isNotBlank()) {
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(base + c.sub))
                        .setMimeType(subtitleMime(c.sub))
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        player.setMediaItem(item.build(), (c.position * 1000).toLong())
        player.prepare()
        player.playWhenReady = true
    }

    /** The subtitle's real URL rides base64-encoded in the proxy path; sniff its type. */
    private fun subtitleMime(path: String): String {
        val raw = runCatching {
            val u = Uri.parse("http://x$path").getQueryParameter("u").orEmpty()
            String(Base64.decode(u, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        }.getOrDefault("").substringBefore('?').lowercase()
        return when {
            raw.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            raw.endsWith(".ass") || raw.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.TEXT_VTT
        }
    }

    /** Stop here and resume on the PC from this exact spot. */
    private fun throwBack() {
        handedBack = true
        val pos = if (loaded.isNotEmpty() && failed.value == null) player.currentPosition / 1000
        else (cast.value?.position ?: 0.0).toLong()
        LiveLink.send("cast/clear", mapOf("pos" to pos.toString()))
        finish()
    }

    override fun onStop() {
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        showing = false
        super.onDestroy()
    }

    companion object {
        /** The player is up (so the remote doesn't open a second one). */
        @Volatile var showing = false
        /** We just threw it back; ignore the still-active cast until the server clears it. */
        @Volatile var handedBack = false
    }
}

@OptIn(UnstableApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun CastScreen(player: ExoPlayer, cast: Cast?, failed: String?, onThrowBack: () -> Unit) {
    var controls by remember { mutableStateOf(true) }
    var flash by remember { mutableStateOf(0 to 0L) }   // side (-1 / +1) and a fresh key

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 3500
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(true)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { v -> controls = v == View.VISIBLE },
                    )
                    // One tap toggles the controls; a double tap on either half seeks 15 s.
                    val view = this
                    val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent) = true
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            if (view.isControllerFullyVisible) view.hideController() else view.showController()
                            return true
                        }
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            val back = e.x < view.width / 2f
                            if (back) player.seekBack() else player.seekForward()
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            flash = (if (back) -1 else 1) to SystemClock.uptimeMillis()
                            return true
                        }
                    })
                    setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (flash.second != 0L) SeekFlash(flash.first, flash.second)

        AnimatedVisibility(
            visible = controls || cast?.resolving != false || failed != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Watching on this phone", style = Type.Small, color = Shu.Vermilion)
                    Text(
                        cast?.let { c -> c.title + if (c.episode > 0) ", episode ${c.episode}" else "" } ?: "Shou",
                        style = Type.Heading, color = Shu.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Pressable(
                    onClick = onThrowBack,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier.height(44.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyph.ThrowBack, null, Modifier.size(18.dp), tint = Shu.Paper)
                        Spacer(Modifier.width(8.dp))
                        Text("Back to the PC", style = Type.Label, color = Shu.Paper)
                    }
                }
            }
        }

        when {
            failed != null -> Column(
                Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(failed, style = Type.Body, color = Shu.Paper, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                PrimaryButton("Keep watching on the PC", onThrowBack, icon = Glyph.ThrowBack)
            }
            cast == null || cast.resolving -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(36.dp), color = Shu.Vermilion, strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text("Catching it on your phone…", style = Type.Meta, color = Shu.Ash)
            }
        }
    }
}

/** A brief "−15 s" / "+15 s" glow on the side you double-tapped. */
@Composable
private fun SeekFlash(side: Int, key: Long) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) {
        a.snapTo(1f)
        a.animateTo(0f, tween(650))
    }
    Box(Modifier.fillMaxSize().alpha(a.value)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.42f)
                .align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .background(
                    Brush.horizontalGradient(
                        if (side < 0) listOf(Shu.Vermilion.copy(alpha = 0.32f), Color.Transparent)
                        else listOf(Color.Transparent, Shu.Vermilion.copy(alpha = 0.32f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (side < 0) Glyph.Rewind else Glyph.Forward, null, Modifier.size(34.dp), tint = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(if (side < 0) "−15 s" else "+15 s", style = Type.Label, color = Color.White)
            }
        }
    }
}
