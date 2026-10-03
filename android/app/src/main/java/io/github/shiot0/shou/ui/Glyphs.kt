package io.github.shiot0.shou.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Shou's icon set, drawn on a 24-unit grid from the same SVG paths as the web remote so
 * both remotes speak one visual language. Strokes are round-capped; [Icon] tints them.
 */
object Glyph {
    private class P(val d: String, val fill: Boolean = false, val w: Float = 2f)

    private fun glyph(name: String, vararg paths: P): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                pathData = addPathNodes(p.d),
                fill = if (p.fill) SolidColor(Color.Black) else null,
                stroke = if (p.fill) null else SolidColor(Color.Black),
                strokeLineWidth = if (p.fill) 0f else p.w,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    val ChevronLeft = glyph("left", P("M15 5l-7 7 7 7", w = 2.4f))
    val ChevronRight = glyph("right", P("M9 5l7 7-7 7", w = 2.4f))
    val ChevronDown = glyph("down", P("M6 9l6 6 6-6", w = 2.4f))
    val ChevronUp = glyph("up", P("M6 15l6-6 6 6", w = 2.4f))
    val Back = glyph("back", P("M15 18l-6-6 6-6", w = 2.2f))

    val Play = glyph("play", P("M8 5.6v12.8a1 1 0 0 0 1.52.85l10.2-6.4a1 1 0 0 0 0-1.7L9.52 4.75A1 1 0 0 0 8 5.6z", fill = true))
    val Pause = glyph(
        "pause",
        P("M7 5h2.6a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", fill = true),
        P("M14.4 5H17a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-2.6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", fill = true),
    )
    val PrevEpisode = glyph(
        "prev",
        P("M6.6 6h1.4a.6.6 0 0 1 .6.6v10.8a.6.6 0 0 1-.6.6H6.6a.6.6 0 0 1-.6-.6V6.6a.6.6 0 0 1 .6-.6z", fill = true),
        P("M19 6.9v10.2a.8.8 0 0 1-1.24.67l-7.9-5.1a.8.8 0 0 1 0-1.34l7.9-5.1A.8.8 0 0 1 19 6.9z", fill = true),
    )
    val NextEpisode = glyph(
        "next",
        P("M16 6h1.4a.6.6 0 0 1 .6.6v10.8a.6.6 0 0 1-.6.6H16a.6.6 0 0 1-.6-.6V6.6A.6.6 0 0 1 16 6z", fill = true),
        P("M5 6.9v10.2a.8.8 0 0 0 1.24.67l7.9-5.1a.8.8 0 0 0 0-1.34l-7.9-5.1A.8.8 0 0 0 5 6.9z", fill = true),
    )
    val Rewind = glyph("rewind", P("M3 4v5h5"), P("M3.5 9a9 9 0 1 1-1.2 5"))
    val Forward = glyph("forward", P("M21 4v5h-5"), P("M20.5 9a9 9 0 1 0 1.2 5"))
    val SkipAhead = glyph("skip", P("M5 6l6 6-6 6", w = 2.2f), P("M12 6l6 6-6 6", w = 2.2f))
    val Stop = glyph("stop", P("M7.2 6h9.6A1.2 1.2 0 0 1 18 7.2v9.6a1.2 1.2 0 0 1-1.2 1.2H7.2A1.2 1.2 0 0 1 6 16.8V7.2A1.2 1.2 0 0 1 7.2 6z", fill = true))

    val Power = glyph("power", P("M12 3v9"), P("M6.5 6.5a8 8 0 1 0 11 0"))
    val Undo = glyph("undo", P("M9 14L4 9l5-5"), P("M4 9h11a5 5 0 0 1 0 10h-3"))
    val Phone = glyph(
        "phone",
        P("M9.4 2.5h5.2A2.4 2.4 0 0 1 17 4.9v14.2a2.4 2.4 0 0 1-2.4 2.4H9.4A2.4 2.4 0 0 1 7 19.1V4.9a2.4 2.4 0 0 1 2.4-2.4z"),
        P("M12 6.5v6M9.2 9.7L12 6.5l2.8 3.2"),
    )
    val ThrowBack = glyph("throwback", P("M10 7l-5 5 5 5"), P("M5 12h11a4 4 0 0 0 0-8h-1"))

    val VolumeDown = glyph("voldown", P("M4 9h4l5-4v14l-5-4H4z", fill = true), P("M17 12h4"))
    val VolumeUp = glyph("volup", P("M3 9h4l5-4v14l-5-4H3z", fill = true), P("M16 8a5 5 0 0 1 0 8"), P("M18.5 5.5a8.5 8.5 0 0 1 0 13"))
    val Mute = glyph("mute", P("M4 9h4l5-4v14l-5-4H4z", fill = true), P("M17 9l4 4M21 9l-4 4"))

    val Search = glyph("search", P(circle(11f, 11f, 6.5f)), P("M20 20l-4.2-4.2"))
    val Filter = glyph("filter", P("M3.5 5h17l-6.5 7.6V19l-4-2v-4.4z"))
    val Close = glyph("close", P("M6 6l12 12M18 6L6 18", w = 2.2f))
    val Plus = glyph("plus", P("M12 5v14M5 12h14", w = 2.2f))
    val Minus = glyph("minus", P("M5 12h14", w = 2.4f))
    val Check = glyph("check", P("M5 12.5l4.5 4.5L19 7.5", w = 2.4f))
    val Tune = glyph(
        "tune",
        P("M4 7h9M19 7h1M4 17h3M13 17h7"),
        P(circle(16f, 7f, 2.4f)), P(circle(10f, 17f, 2.4f)),
    )
    val More = glyph(
        "more",
        P(circle(12f, 5.5f, 1.7f), fill = true), P(circle(12f, 12f, 1.7f), fill = true), P(circle(12f, 18.5f, 1.7f), fill = true),
    )
    val Signal = glyph("signal", P("M5 12.5a10 10 0 0 1 14 0"), P("M8 15.5a6 6 0 0 1 8 0"), P(circle(12f, 18.5f, 1.3f), fill = true))
    val Monitor = glyph(
        "monitor",
        P("M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"),
        P("M8 20h8M12 16v4"),
    )
    val Bookmark = glyph("bookmark", P("M7.2 3.5h9.6A1.2 1.2 0 0 1 18 4.7V20.5l-6-4-6 4V4.7a1.2 1.2 0 0 1 1.2-1.2z"))
    val Bolt = glyph("bolt", P("M13 2.5L4.5 13.5h6.5l-1 8 8.5-11h-6.5z"))
    val Star = glyph("star", P("M12 2.9l2.75 5.75 6.3.78-4.62 4.37 1.17 6.25L12 16.98l-5.6 3.07 1.17-6.25-4.62-4.37 6.3-.78z", fill = true))
}
