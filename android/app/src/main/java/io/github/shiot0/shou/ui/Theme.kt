package io.github.shiot0.shou.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.shiot0.shou.R

/*
 * Shou's "projection booth": the room stays dark and the film supplies the colour.
 * Every screen is lit by the focused show's own artwork and AniList colour; vermilion
 * (朱, shu) is held back for the one thing on screen you're most likely to press.
 */
object Shu {
    val Ink = Color(0xFF100E12)        // the room: warm, not pitch black
    val Booth = Color(0xFF1B181E)      // soft raised surfaces (cards, rows)
    val Booth2 = Color(0xFF25222A)     // buttons and chips on the room or on cards
    val Booth3 = Color(0xFF302C35)     // pressed / selected surfaces
    val Rule = Color(0xFF2E2A32)       // the rare divider
    val Paper = Color(0xFFF5EFE6)      // primary text
    val Ash = Color(0xFFA79F95)        // secondary text (7:1 on Ink)
    val Vermilion = Color(0xFFFF4A32)  // the one primary action
    val VermilionDeep = Color(0xFFD63B27)
    val Jade = Color(0xFF5AD6A0)       // live / caught up / watching
    val Sky = Color(0xFF6AB0FF)        // completed
    val Rose = Color(0xFFD98B8B)       // dropped / destructive
}

val Mincho = FontFamily(
    Font(R.font.shippori_mincho_bold, FontWeight.Bold),
    Font(R.font.shippori_mincho_extrabold, FontWeight.ExtraBold),
)

val Gothic = FontFamily(
    Font(R.font.zen_kaku_regular, FontWeight.Normal),
    Font(R.font.zen_kaku_medium, FontWeight.Medium),
    Font(R.font.zen_kaku_bold, FontWeight.Bold),
    Font(R.font.zen_kaku_black, FontWeight.Black),
)

/** The type scale. Mincho is for the names of things (shows, the brand); Gothic runs
 *  everything you read or press. Numbers that tick use tabular figures. */
object Type {
    val Display = TextStyle(fontFamily = Mincho, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 35.sp, letterSpacing = 0.em)
    val Title = TextStyle(fontFamily = Mincho, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 29.sp)
    val TitleSmall = TextStyle(fontFamily = Mincho, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp)
    val Heading = TextStyle(fontFamily = Gothic, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp)
    /** Section names ("Watching", "Continue watching") — editorial, like a magazine. */
    val Section = TextStyle(fontFamily = Mincho, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 27.sp)
    val Body = TextStyle(fontFamily = Gothic, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp)
    val BodyStrong = TextStyle(fontFamily = Gothic, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 21.sp)
    val Label = TextStyle(fontFamily = Gothic, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.01.em)
    val Meta = TextStyle(fontFamily = Gothic, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val Small = TextStyle(fontFamily = Gothic, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val Time = TextStyle(fontFamily = Gothic, fontWeight = FontWeight.Medium, fontSize = 12.sp, fontFeatureSettings = "tnum")
}

@Composable
fun ShouTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Shu.Vermilion,
            onPrimary = Color.White,
            primaryContainer = Shu.VermilionDeep,
            onPrimaryContainer = Color.White,
            secondary = Shu.Jade,
            background = Shu.Ink,
            onBackground = Shu.Paper,
            surface = Shu.Booth,
            onSurface = Shu.Paper,
            surfaceVariant = Shu.Booth2,
            onSurfaceVariant = Shu.Ash,
            surfaceContainerLow = Shu.Booth,
            surfaceContainer = Shu.Booth,
            surfaceContainerHigh = Shu.Booth2,
            surfaceContainerHighest = Shu.Booth2,
            outline = Shu.Rule,
            outlineVariant = Shu.Rule,
            error = Shu.Rose,
            scrim = Color.Black,
        ),
        typography = Typography(
            bodyLarge = Type.Body,
            bodyMedium = Type.Meta,
            labelLarge = Type.Label,
            titleLarge = Type.Title,
            titleMedium = Type.Heading,
        ),
        content = content,
    )
}
