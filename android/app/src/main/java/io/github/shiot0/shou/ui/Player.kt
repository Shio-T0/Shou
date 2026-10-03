package io.github.shiot0.shou.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiot0.shou.KioskState
import io.github.shiot0.shou.Playing
import io.github.shiot0.shou.RemoteViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/** An anime opening is about 1:30; this jumps you past it in one tap. */
private const val SKIP_OPENING_SECONDS = 85

/**
 * Now playing on the PC: big art, a timeline you can drag, and only the controls you
 * reach for while watching — ordered by how often you'll want them.
 * [onCollapse] is set when this was opened from the mini player.
 */
@Composable
fun Player(s: KioskState, vm: RemoteViewModel, onCollapse: (() -> Unit)? = null) {
    val p = s.playing ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The controls get their room first; the artwork takes whatever height is left.
        val coverH: Dp = (maxHeight - 500.dp).coerceIn(120.dp, 330.dp)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onCollapse != null) {
                Box(Modifier.fillMaxWidth()) {
                    RoundButton(Glyph.ChevronDown, "Close the player", onCollapse, size = 44.dp, color = Color.Transparent, tint = Shu.Ash)
                }
            }
            Spacer(Modifier.weight(0.6f))
            Cover(
                p.cover, p.color,
                Modifier
                    .height(coverH)
                    .aspectRatio(0.7f)
                    .shadow(40.dp, RoundedCornerShape(20.dp), ambientColor = showColor(p.color), spotColor = showColor(p.color)),
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                p.title, style = Type.Title.copy(fontSize = 26.sp, lineHeight = 31.sp), color = Shu.Paper,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (p.total != null) "Episode ${p.episode} of ${p.total}" else "Episode ${p.episode}",
                style = Type.Body, color = Shu.Ash,
            )
            Spacer(Modifier.height(20.dp))
            if (p.live) Scrubber(p, onSeek = { target -> vm.seekBy((target - p.position).roundToInt()) })
            else Starting(s.message)
            Spacer(Modifier.weight(0.4f))
            Transport(p, vm)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    "Skip opening", { vm.seekBy(SKIP_OPENING_SECONDS) }, Modifier.weight(1f),
                    icon = Glyph.SkipAhead, height = 52.dp, enabled = p.live,
                )
                GhostButton(
                    "Watch on phone", vm::throwToPhone, Modifier.weight(1f),
                    icon = Glyph.Phone, height = 52.dp, enabled = p.live,
                )
            }
            Spacer(Modifier.height(10.dp))
            Volume(vm)
            Spacer(Modifier.height(6.dp))
            GhostButton("Stop watching", vm::back, icon = Glyph.Stop, tint = Shu.Ash, color = Color.Transparent, height = 46.dp)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun Starting(message: String) {
    Column(Modifier.fillMaxWidth().height(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth().height(3.dp),
            color = Shu.Vermilion, trackColor = Color.White.copy(alpha = 0.12f), strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            message.takeIf { "backup" in it } ?: "Finding a source and starting the player…",
            style = Type.Small, color = Shu.Ash, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The episode timeline. Drag or tap anywhere on it to seek the PC; the thumb holds your
 * target until the player catches up, then follows the live position again.
 */
@Composable
private fun Scrubber(p: Playing, onSeek: (Double) -> Unit) {
    val haptics = rememberHaptics()
    var drag by remember { mutableStateOf<Float?>(null) }
    var hold by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    val live = (p.position / p.duration).toFloat().coerceIn(0f, 1f)

    LaunchedEffect(p.position) {
        val h = hold ?: return@LaunchedEffect
        if (abs(live - h.first) * p.duration < 4 || SystemClock.uptimeMillis() - h.second > 4000) hold = null
    }
    val target = drag ?: hold?.first
    val smooth by animateFloatAsState(live, tween(1000, easing = LinearEasing), label = "scrub")
    val shown = target ?: smooth

    fun commit(f: Float) {
        haptics.confirm()
        hold = f to SystemClock.uptimeMillis()
        onSeek(f * p.duration)
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(p.duration) {
                    detectTapGestures { o -> commit((o.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(p.duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let(::commit); drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2
                val w = size.width
                val stroke = (if (drag != null) 7 else 5).dp.toPx()
                drawLine(Color.White.copy(alpha = 0.14f), Offset(0f, y), Offset(w, y), stroke, StrokeCap.Round)
                drawLine(Shu.Vermilion, Offset(0f, y), Offset(w * shown, y), stroke, StrokeCap.Round)
                drawCircle(Color.White, (if (drag != null) 11 else 8).dp.toPx(), Offset(w * shown, y))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(clock(shown * p.duration), style = Type.Time, color = if (drag != null) Shu.Paper else Shu.Ash)
            Spacer(Modifier.weight(1f))
            Text("−" + clock(p.duration - shown * p.duration), style = Type.Time, color = Shu.Ash)
        }
    }
}

@Composable
private fun Transport(p: Playing, vm: RemoteViewModel) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(Glyph.PrevEpisode, "Previous episode", vm::prevEpisode, size = 48.dp, iconSize = 22.dp, color = Color.Transparent, tint = Shu.Ash)
        SeekButton(Glyph.Rewind, "Back 15 seconds") { vm.seekBy(-15) }
        Pressable(
            onClick = { haptics.confirm(); vm.pause() },
            shape = CircleShape,
            color = Shu.Vermilion,
            contentDescription = if (p.paused) "Play" else "Pause",
            modifier = Modifier.size(84.dp),
        ) {
            Icon(if (p.paused) Glyph.Play else Glyph.Pause, null, Modifier.size(36.dp), tint = Color.White)
        }
        SeekButton(Glyph.Forward, "Forward 15 seconds") { vm.seekBy(15) }
        RoundButton(Glyph.NextEpisode, "Next episode", vm::nextEpisode, size = 48.dp, iconSize = 22.dp, color = Color.Transparent, tint = Shu.Ash)
    }
}

/** A circular-arrow seek button with its step written inside the arc. */
@Composable
private fun SeekButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.tick(); onClick() },
        shape = CircleShape,
        color = Shu.Booth2,
        contentDescription = label,
        modifier = Modifier.size(62.dp),
    ) {
        Icon(icon, null, Modifier.size(34.dp), tint = Shu.Paper)
        Text("15", style = Type.Small.copy(fontSize = 10.sp), color = Shu.Paper, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The PC's volume as one soft rocker, mute in the middle. The phone's own volume
 *  buttons do the same while the remote is open (see Settings). */
@Composable
private fun Volume(vm: RemoteViewModel) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(Shu.Booth2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VolumeKey(Glyph.VolumeDown, "PC volume down", Modifier.weight(1f)) { vm.volume("down") }
        Pressable(
            onClick = { haptics.tick(); vm.volume("mute") },
            shape = CircleShape, color = Color.Transparent,
            contentDescription = "Mute the PC",
            modifier = Modifier.weight(1.4f).height(52.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.Mute, null, Modifier.size(18.dp), tint = Shu.Ash)
                Spacer(Modifier.width(8.dp))
                Text("Mute", style = Type.Label, color = Shu.Ash)
            }
        }
        VolumeKey(Glyph.VolumeUp, "PC volume up", Modifier.weight(1f)) { vm.volume("up") }
    }
}

@Composable
private fun VolumeKey(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.tick(); onClick() },
        shape = CircleShape,
        color = Color.Transparent,
        contentDescription = label,
        modifier = modifier.height(52.dp),
    ) { Icon(icon, null, Modifier.size(22.dp), tint = Shu.Paper) }
}
