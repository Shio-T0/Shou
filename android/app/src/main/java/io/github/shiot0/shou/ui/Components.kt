package io.github.shiot0.shou.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Show colour from AniList, or a neutral booth tone when a show has none. */
fun showColor(argb: Int?): Color = argb?.let { Color(it) } ?: Color(0xFF2A2633)

/** Haptics that feel like a remote: a light tick for steps, a firmer one for actions. */
class Haptics(private val view: android.view.View) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun confirm() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Poster art with the show's colour behind it while it loads. */
@Composable
fun Cover(
    url: String,
    color: Int?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    val ctx = LocalContext.current
    Box(modifier.clip(shape).background(showColor(color).copy(alpha = 0.55f))) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = remember(url) { ImageRequest.Builder(ctx).data(url).crossfade(260).build() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The room's light: the focused show's art, enormous and out of focus, spilling down
 * from the top of the screen and tinted by its AniList colour. It cross-fades whenever
 * the PC moves to a different show, so the phone visibly follows the big screen.
 */
@Composable
fun Ambient(art: String, color: Int?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tint by animateColorAsState(showColor(color), tween(700), label = "ambient-tint")
    Box(modifier.fillMaxSize().background(Shu.Ink)) {
        Crossfade(targetState = art, animationSpec = tween(700), label = "ambient-art") { url ->
            if (url.isNotBlank()) {
                AsyncImage(
                    // A tiny decode, scaled up, is already soft; blur() finishes it on 12+.
                    model = remember(url) { ImageRequest.Builder(ctx).data(url).size(48, 72).build() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.62f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.72f)
                        .scale(1.25f)
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = 0.30f),
                    0.30f to Shu.Ink.copy(alpha = 0.55f),
                    0.62f to Shu.Ink,
                    1f to Shu.Ink,
                ),
            ),
        )
    }
}

/** Spoken label for icon-only controls. */
fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }

/** Thin progress rule. */
@Composable
fun ProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Shu.Vermilion,
    track: Color = Color.White.copy(alpha = 0.12f),
    height: Dp = 3.dp,
) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "progress")
    Box(modifier.height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(CircleShape).background(color))
    }
}

/** A press-scaled clickable surface, the base of every button in the remote. */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    color: Color = Shu.Booth,
    border: BorderStroke? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.95f else 1f, tween(90), label = "press")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = color,
        contentColor = Shu.Paper,
        border = border,
        interactionSource = source,
        modifier = modifier.scale(s).then(
            if (contentDescription != null) Modifier.semanticsLabel(contentDescription) else Modifier,
        ),
    ) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}

/** The vermilion button: one per screen, for the thing you came to do. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.confirm(); onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(height / 2),
        color = if (enabled) Shu.Vermilion else Shu.Booth2,
        modifier = modifier.height(height),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 18.dp),
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) Color.White else Shu.Ash)
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text,
                style = Type.Label.copy(fontSize = 15.sp),
                color = if (enabled) Color.White else Shu.Ash,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Secondary action: quiet, outlined. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Shu.Paper,
    height: Dp = 48.dp,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.tick(); onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(height / 2),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Shu.Rule),
        modifier = modifier.height(height),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            val c = if (enabled) tint else Shu.Ash.copy(alpha = 0.5f)
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = c)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = Type.Label, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Round icon button used across the transport and top bar. */
@Composable
fun RoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    iconSize: Dp = 24.dp,
    color: Color = Shu.Booth,
    tint: Color = Shu.Paper,
    border: Boolean = true,
    enabled: Boolean = true,
    haptic: Boolean = true,
) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { if (haptic) haptics.tick(); onClick() },
        enabled = enabled,
        shape = CircleShape,
        color = color,
        border = if (border) BorderStroke(1.dp, Shu.Rule) else null,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = if (enabled) tint else Shu.Ash.copy(alpha = 0.45f))
    }
}

/** Colour for an AniList list status. */
fun statusColor(status: String?): Color = when (status) {
    "CURRENT", "REPEATING" -> Shu.Jade
    "PLANNING" -> Shu.Vermilion
    "COMPLETED" -> Shu.Sky
    "DROPPED" -> Shu.Rose
    "PAUSED" -> Shu.Paper
    else -> Shu.Ash
}

@Composable
fun StatusPill(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.38f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = Type.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = color, maxLines = 1)
    }
}

/** Mincho-on-booth seal: the 朱 mark that stands for a Shou PC. */
@Composable
fun Seal(modifier: Modifier = Modifier, size: Dp = 36.dp, lit: Boolean = true) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(if (lit) Shu.Vermilion.copy(alpha = 0.14f) else Shu.Booth2)
            .border(1.dp, if (lit) Shu.Vermilion.copy(alpha = 0.35f) else Shu.Rule, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("朱", style = TextStyle(fontFamily = Mincho, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, fontSize = (size.value * 0.52f).sp), color = Shu.Vermilion)
    }
}

/** "1:23" / "1:02:03" for playback positions. */
fun clock(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
