package io.github.shiot0.shou.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiot0.shou.Card
import io.github.shiot0.shou.KioskState
import io.github.shiot0.shou.RemoteViewModel
import io.github.shiot0.shou.ResumeEntry

/**
 * Browsing a list: the show the PC is focused on, every other show as a poster you can
 * tap to jump to, what you left half-watched, and a thumb-height dock to drive it all.
 */
@Composable
fun Browse(s: KioskState, pending: Int?, pcName: String, vm: RemoteViewModel) {
    val focusedIdx = (pending ?: s.cursor).coerceIn(0, (s.items.size - 1).coerceAtLeast(0))
    val item = s.items.getOrNull(focusedIdx)
    val onGrid = s.view == "grid" && item != null

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(10.dp))
            when {
                s.view == "sequel" && s.sequel != null && item != null -> SequelFeature(s, item)
                onGrid -> Feature(item!!)
                else -> StatusFeature(s, pcName)
            }
            if (s.items.size > 1 && (onGrid || s.view == "sequel")) {
                PosterRail(s, focusedIdx, onTap = { i ->
                    if (i == focusedIdx && pending == null) vm.select() else vm.focus(i)
                })
            }
            if (s.history.isNotEmpty()) ContinueRail(s.history, vm)
            Spacer(Modifier.height(20.dp))
        }
        Dock(s, item, vm)
    }
}

// --- The focused show ------------------------------------------------------------- //

/** Shrink long titles so the feature block keeps one height and nothing below it jumps. */
private fun titleStyle(title: String) = when {
    title.length <= 18 -> Type.Display
    title.length <= 40 -> Type.Title
    else -> Type.TitleSmall
}

@Composable
private fun Feature(item: Card) {
    Row(Modifier.fillMaxWidth().height(174.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
        Cover(
            item.cover, item.color,
            Modifier.size(width = 122.dp, height = 174.dp).shadow(22.dp, RoundedCornerShape(12.dp), ambientColor = showColor(item.color), spotColor = showColor(item.color)),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
            Text(
                item.title,
                style = titleStyle(item.title),
                color = Shu.Paper,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            val (headline, detail) = episodeCopy(item)
            Text(headline, style = Type.BodyStrong, color = if (item.caughtUp) Shu.Jade else Shu.Paper)
            Spacer(Modifier.height(3.dp))
            Text(detail, style = Type.Meta, color = Shu.Ash)
            Spacer(Modifier.height(12.dp))
            ProgressLine(item.fraction, Modifier.fillMaxWidth(), color = if (item.caughtUp) Shu.Jade else Shu.Vermilion)
        }
    }
}

/** "Episode 3 is next" + "2 of 13 watched", phrased for airing and finished shows. */
private fun episodeCopy(c: Card): Pair<String, String> {
    val next = c.progress + 1
    val watched = when {
        c.total != null -> "${c.progress} of ${c.total} watched"
        c.available != null -> "${c.progress} watched, ${c.available} aired so far"
        else -> "${c.progress} watched"
    }
    return when {
        c.caughtUp && c.total != null && c.progress >= c.total -> "You've seen it all" to watched
        c.caughtUp -> "You're caught up" to "Episode $next hasn't aired yet"
        c.progress == 0 -> "Starts at episode 1" to watched
        else -> "Episode $next is next" to watched
    }
}

@Composable
private fun SequelFeature(s: KioskState, item: Card) {
    val sq = s.sequel!!
    Row(Modifier.fillMaxWidth().height(174.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
        Cover(item.cover, item.color, Modifier.size(width = 122.dp, height = 174.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
            Text("Finished ${sq.finished}", style = Type.Meta, color = Shu.Jade, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(sq.sequelTitle, style = titleStyle(sq.sequelTitle), color = Shu.Paper, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text("The story continues in this sequel.", style = Type.Meta, color = Shu.Ash)
        }
    }
}

/** Closed kiosk, loading, empty list or an error — said plainly, with the way forward. */
@Composable
private fun StatusFeature(s: KioskState, pcName: String) {
    val (title, body) = when {
        s.kioskClosed -> "Shou is closed" to "Open it to put your list on ${pcName.ifBlank { "the PC" }}'s screen."
        s.view == "loading" -> "Loading" to s.message.ifBlank { "Fetching your list from AniList…" }
        s.view == "empty" -> "Nothing here yet" to (s.message.ifBlank { "This list is empty." } + " Find something new in Search.")
        s.view == "error" && s.items.isEmpty() -> "Couldn't load your list" to s.message
        s.view == "error" -> "That didn't play" to s.message.replace(" Press Back and try another.", "")
        else -> "Shou" to s.message
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp)) {
        if (s.view == "loading" && !s.kioskClosed) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Shu.Vermilion, strokeWidth = 2.dp)
            Spacer(Modifier.height(18.dp))
        }
        Text(title, style = Type.Display, color = Shu.Paper)
        Spacer(Modifier.height(10.dp))
        Text(body, style = Type.Body, color = Shu.Ash)
    }
}

// --- Poster rail -------------------------------------------------------------------- //

@Composable
private fun PosterRail(s: KioskState, focusedIdx: Int, onTap: (Int) -> Unit) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptics = rememberHaptics()

    // Keep the PC's focus centred as it moves (from the phone or the kiosk keyboard).
    LaunchedEffect(focusedIdx, s.items.size) {
        val viewport = listState.layoutInfo.viewportSize.width
        val item = with(density) { 82.dp.roundToPx() }
        listState.animateScrollToItem(focusedIdx, -((viewport - item) / 2).coerceAtLeast(0))
    }

    Column(Modifier.padding(top = 26.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
            Text(if (s.list == "planned") "Planned" else "Watching", style = Type.Heading, color = Shu.Paper)
            Spacer(Modifier.weight(1f))
            Text("${focusedIdx + 1} of ${s.items.size}", style = Type.Time.copy(fontSize = 13.sp), color = Shu.Ash)
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            itemsIndexed(s.items, key = { _, c -> c.id }) { i, c ->
                val on = i == focusedIdx
                val scale by animateFloatAsState(if (on) 1f else 0.92f, tween(220), label = "poster-scale")
                val dim by animateFloatAsState(if (on) 1f else 0.62f, tween(220), label = "poster-dim")
                Column(
                    Modifier
                        .width(82.dp)
                        .clickable(remember { MutableInteractionSource() }, null) { haptics.tick(); onTap(i) },
                ) {
                    Box(Modifier.scale(scale)) {
                        Cover(
                            c.cover, c.color,
                            Modifier
                                .size(width = 82.dp, height = 117.dp)
                                .alpha(dim)
                                .then(if (on) Modifier.border(2.dp, Shu.Vermilion, RoundedCornerShape(10.dp)) else Modifier),
                        )
                        if (on) {
                            Box(
                                Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape)
                                    .background(Shu.Ink.copy(alpha = 0.62f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Glyph.Play, "Play", Modifier.size(18.dp), tint = Color.White) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(
                        c.fraction, Modifier.fillMaxWidth().alpha(dim),
                        color = if (c.caughtUp) Shu.Jade else Shu.Vermilion, height = 2.5.dp,
                    )
                }
            }
        }
    }
}

// --- Continue watching ------------------------------------------------------------- //

@Composable
private fun ContinueRail(history: List<ResumeEntry>, vm: RemoteViewModel) {
    Column(Modifier.padding(top = 26.dp)) {
        Text("Continue watching", style = Type.Heading, color = Shu.Paper, modifier = Modifier.padding(horizontal = 20.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            items(history, key = { "${it.mediaId}:${it.episode}" }) { e -> ResumeCard(e, vm) }
        }
    }
}

@Composable
private fun ResumeCard(e: ResumeEntry, vm: RemoteViewModel) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.confirm(); vm.resume(e) },
        shape = RoundedCornerShape(18.dp),
        color = Shu.Booth.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, Shu.Rule),
        modifier = Modifier.width(250.dp).height(92.dp),
        contentDescription = "Resume ${e.title}, episode ${e.episode}",
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Cover(e.cover, e.color, Modifier.size(width = 50.dp, height = 72.dp), RoundedCornerShape(8.dp))
                Box(
                    Modifier.align(Alignment.Center).size(26.dp).clip(CircleShape).background(Shu.Ink.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyph.Play, null, Modifier.size(14.dp), tint = Color.White) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = Type.Label, color = Shu.Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("Episode ${e.episode} at ${clock(e.position)}", style = Type.Time, color = Shu.Ash)
                Spacer(Modifier.height(8.dp))
                ProgressLine(e.fraction, Modifier.fillMaxWidth(), height = 2.5.dp)
            }
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier.align(Alignment.Top).size(30.dp).clip(CircleShape)
                    .clickable { haptics.tick(); vm.forget(e) },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Close, "Remove from Continue watching", Modifier.size(14.dp), tint = Shu.Ash) }
        }
    }
}

// --- The dock --------------------------------------------------------------------- //

/** ‹  [ the one thing to do ]  › — plus Open/Back. Sits in the thumb zone. */
@Composable
private fun Dock(s: KioskState, item: Card?, vm: RemoteViewModel) {
    val canStep = s.view == "grid" && s.items.size > 1
    data class Primary(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector?, val enabled: Boolean, val run: () -> Unit)

    val primary = when {
        s.kioskClosed -> Primary("Open Shou", Glyph.Power, true, vm::open)
        s.view == "sequel" -> Primary("Watch the sequel", Glyph.Play, true, vm::select)
        s.view == "grid" && item != null && !item.caughtUp ->
            Primary("Play episode ${item.progress + 1}", Glyph.Play, true, vm::select)
        s.view == "grid" && item != null -> Primary("Select", Glyph.Play, true, vm::select)
        s.view == "loading" -> Primary("Loading…", null, false) {}
        s.view == "empty" -> Primary("Search AniList", Glyph.Search, true) { vm.showList("search") }
        s.view == "error" && s.items.isEmpty() -> Primary("Try again", null, true) { vm.showList(s.list) }
        s.view == "error" -> Primary("Back to the list", Glyph.Undo, true, vm::back)
        else -> Primary("Select", null, true, vm::select)
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Glyph.ChevronLeft, "Previous show", vm::left, size = 64.dp, iconSize = 26.dp, enabled = canStep, color = Shu.Booth.copy(alpha = 0.9f))
            Spacer(Modifier.width(10.dp))
            PrimaryButton(primary.label, primary.run, Modifier.weight(1f), icon = primary.icon, enabled = primary.enabled, height = 64.dp)
            Spacer(Modifier.width(10.dp))
            RoundButton(Glyph.ChevronRight, "Next show", vm::right, size = 64.dp, iconSize = 26.dp, enabled = canStep, color = Shu.Booth.copy(alpha = 0.9f))
        }
        if (!s.kioskClosed) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Open on PC", vm::open, Modifier.weight(1f), icon = Glyph.Power, tint = Shu.Ash)
                GhostButton("Back", vm::back, Modifier.weight(1f), icon = Glyph.Undo, tint = Shu.Ash)
            }
        }
    }
}
