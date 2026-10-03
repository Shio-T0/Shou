package io.github.shiot0.shou.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiot0.shou.KioskState
import io.github.shiot0.shou.RemoteViewModel

/** A series just ended: rate it. ‹ › on the PC remote adjust the score; this is them, larger. */
@Composable
fun RatingPanel(s: KioskState, vm: RemoteViewModel) {
    val r = s.rating ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverH = (maxHeight - 430.dp).coerceIn(110.dp, 280.dp)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Cover(
                r.cover, r.color,
                Modifier.height(coverH).aspectRatio(0.7f)
                    .shadow(36.dp, RoundedCornerShape(14.dp), ambientColor = showColor(r.color), spotColor = showColor(r.color)),
                RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(22.dp))
            Text("Series complete", style = Type.Label, color = Shu.Jade)
            Spacer(Modifier.height(6.dp))
            Text(r.title, style = Type.Title, color = Shu.Paper, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(24.dp))
            Stars(r.stars)
            Spacer(Modifier.height(10.dp))
            Text("${r.scoreText} / ${r.maxText}", style = Type.Title, color = Shu.Paper)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val adjustable = !r.done && !r.submitting
                RoundButton(Glyph.Minus, "Lower score", vm::left, size = 64.dp, enabled = adjustable)
                Spacer(Modifier.width(10.dp))
                PrimaryButton(
                    when {
                        r.done -> "Rating saved"
                        r.submitting -> "Saving…"
                        else -> "Save rating"
                    },
                    vm::select, Modifier.weight(1f), icon = if (r.done) Glyph.Check else null,
                    enabled = adjustable, height = 64.dp,
                )
                Spacer(Modifier.width(10.dp))
                RoundButton(Glyph.Plus, "Raise score", vm::right, size = 64.dp, enabled = adjustable)
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(if (r.done) "Back to the list" else "Skip rating", vm::back, Modifier.fillMaxWidth(), tint = Shu.Ash)
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Five stars, filled to a fractional [value] out of 5. */
@Composable
private fun Stars(value: Double) {
    val v by animateFloatAsState(value.toFloat().coerceIn(0f, 5f), tween(260), label = "stars")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 5) {
            val fill = (v - i).coerceIn(0f, 1f)
            Box(Modifier.size(38.dp)) {
                Icon(Glyph.Star, null, Modifier.fillMaxSize(), tint = Color.White.copy(alpha = 0.13f))
                Icon(
                    Glyph.Star, null,
                    Modifier.fillMaxSize().drawWithContent {
                        clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                    },
                    tint = Shu.Vermilion,
                )
            }
        }
    }
}
