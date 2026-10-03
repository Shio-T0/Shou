package io.github.shiot0.shou.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
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

/** Now playing on the PC: the episode's art, a scrubber you can drag, and the controls. */
@Composable
fun Player(s: KioskState, vm: RemoteViewModel) {
    val p = s.playing ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Give the controls their room first; the artwork takes whatever height is left.
        val coverH: Dp = (maxHeight - 480.dp).coerceIn(110.dp, 300.dp)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.5f))
            Cover(
                p.cover, p.color,
                Modifier
                    .height(coverH)
                    .aspectRatio(0.7f)
                    .shadow(36.dp, RoundedCornerShape(14.dp), ambientColor = showColor(p.color), spotColor = showColor(p.color)),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                p.title, style = Type.Title, color = Shu.Paper, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (p.total != null) "Episode ${p.episode} of ${p.total}" else "Episode ${p.episode}",
                style = Type.Meta, color = Shu.Ash,
            )
            Spacer(Modifier.height(18.dp))
            if (p.live) {
                Scrubber(p, onSeek = { target -> vm.seekBy((target - p.position).roundToInt()) })
            } else {
                Starting(s.message)
            }
            Spacer(Modifier.weight(0.5f))
            Transport(p, vm)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Jump("−30 s", Modifier.weight(1f)) { vm.seekBy(-30) }
                Jump("Skip opening", Modifier.weight(1.5f), Glyph.SkipAhead) { vm.seekBy(SKIP_OPENING_SECONDS) }
                Jump("+30 s", Modifier.weight(1f)) { vm.seekBy(30) }
            }
            Spacer(Modifier.height(12.dp))
            Volume(vm)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Watch on phone", vm::throwToPhone, Modifier.weight(1.3f), icon = Glyph.Phone, tint = Shu.Vermilion, enabled = p.live)
                GhostButton("Stop", vm::back, Modifier.weight(1f), icon = Glyph.Stop, tint = Shu.Ash)
            }
            Spacer(Modifier.height(10.dp))
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
                .height(30.dp)
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
                val stroke = 4.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.14f), Offset(0f, y), Offset(w, y), stroke, StrokeCap.Round)
                drawLine(Shu.Vermilion, Offset(0f, y), Offset(w * shown, y), stroke, StrokeCap.Round)
                val r = (if (drag != null) 10 else 7).dp.toPx()
                drawCircle(Color.White, r, Offset(w * shown, y))
                drawCircle(Shu.Vermilion, r - 2.5.dp.toPx(), Offset(w * shown, y))
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(Glyph.PrevEpisode, "Previous episode", vm::prevEpisode, size = 50.dp, iconSize = 22.dp, color = Color.Transparent, border = false, tint = Shu.Ash)
        SeekButton(Glyph.Rewind, "Back 15 seconds") { vm.seekBy(-15) }
        val haptics = rememberHaptics()
        Pressable(
            onClick = { haptics.confirm(); vm.pause() },
            shape = CircleShape,
            color = Shu.Vermilion,
            contentDescription = if (p.paused) "Play" else "Pause",
            modifier = Modifier.size(80.dp),
        ) {
            Icon(if (p.paused) Glyph.Play else Glyph.Pause, null, Modifier.size(34.dp), tint = Color.White)
        }
        SeekButton(Glyph.Forward, "Forward 15 seconds") { vm.seekBy(15) }
        RoundButton(Glyph.NextEpisode, "Next episode", vm::nextEpisode, size = 50.dp, iconSize = 22.dp, color = Color.Transparent, border = false, tint = Shu.Ash)
    }
}

/** A circular-arrow seek button with its step written inside the arc. */
@Composable
private fun SeekButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.tick(); onClick() },
        shape = CircleShape,
        color = Shu.Booth.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, Shu.Rule),
        contentDescription = label,
        modifier = Modifier.size(60.dp),
    ) {
        Icon(icon, null, Modifier.size(34.dp), tint = Shu.Paper)
        Text("15", style = Type.Small.copy(fontSize = 10.sp), color = Shu.Paper, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Jump(label: String, modifier: Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.tick(); onClick() },
        shape = RoundedCornerShape(14.dp),
        color = Shu.Booth.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, Shu.Rule),
        modifier = modifier.height(44.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = Shu.Vermilion)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = Type.Label.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"), color = Shu.Paper, maxLines = 1)
        }
    }
}

/** The PC's volume, as one labelled rocker plus mute. The phone's own volume buttons
 *  do the same while the remote is open (see Settings). */
@Composable
private fun Volume(vm: RemoteViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .clip(CircleShape)
                .border(1.dp, Shu.Rule, CircleShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VolumeKey(Glyph.VolumeDown, "PC volume down") { vm.volume("down") }
            Text("PC volume", style = Type.Meta, color = Shu.Ash, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            VolumeKey(Glyph.VolumeUp, "PC volume up") { vm.volume("up") }
        }
        Spacer(Modifier.width(10.dp))
        RoundButton(Glyph.Mute, "Mute the PC", { vm.volume("mute") }, size = 48.dp, iconSize = 20.dp, color = Color.Transparent, tint = Shu.Ash)
    }
}

@Composable
private fun VolumeKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.tick(); onClick() },
        shape = CircleShape,
        color = Color.Transparent,
        contentDescription = label,
        modifier = Modifier.size(width = 64.dp, height = 48.dp),
    ) { Icon(icon, null, Modifier.size(21.dp), tint = Shu.Paper) }
}
