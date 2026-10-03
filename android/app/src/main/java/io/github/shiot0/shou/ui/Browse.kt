package io.github.shiot0.shou.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.shiot0.shou.Card
import io.github.shiot0.shou.KioskState
import io.github.shiot0.shou.Playing
import io.github.shiot0.shou.RemoteViewModel
import io.github.shiot0.shou.ResumeEntry
import io.github.shiot0.shou.SignInActivity
import kotlinx.coroutines.launch

/**
 * Your list, as a wall of posters you browse with your thumb. Tapping one moves the
 * PC to it; the bar at the bottom always says what Play will do (or what's playing).
 */
@Composable
fun Browse(s: KioskState, pending: Int?, pcName: String, vm: RemoteViewModel, onExpandPlayer: () -> Unit) {
    val focusedIdx = (pending ?: s.cursor).coerceIn(0, (s.items.size - 1).coerceAtLeast(0))
    val focused = s.items.getOrNull(focusedIdx)
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val headerCount = (if (s.history.isNotEmpty()) 1 else 0) + (if (s.view == "error" && s.items.isNotEmpty()) 1 else 0) + 1

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                s.kioskClosed -> Closed(s, pcName, vm)
                s.items.isEmpty() && s.view == "loading" -> Skeleton(s)
                s.items.isEmpty() && s.view == "empty" -> Message(
                    if (s.list == "planned") "Nothing planned yet" else "Nothing to watch yet",
                    "Find something in Search and add it to your lists.",
                    "Search AniList",
                ) { vm.showList("search") }
                s.items.isEmpty() && s.view == "error" -> ListError(s, vm)
                else -> {
                    // Follow the PC when its focus moves on its own (kiosk keyboard), but
                    // never yank the grid while you're steering from here.
                    LaunchedEffect(s.cursor) {
                        if (pending != null) return@LaunchedEffect
                        val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
                        val target = headerCount + s.cursor
                        if (visible.isNotEmpty() && target !in visible) gridState.animateScrollToItem(target)
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (s.history.isNotEmpty()) fullWidth { ContinueWatching(s.history, vm) }
                        if (s.view == "error" && s.items.isNotEmpty()) fullWidth {
                            Notice("That didn't play", s.message.replace(" Press Back and try another.", ""), "Back to the list", vm::back)
                        }
                        fullWidth {
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                                Text(if (s.list == "planned") "Planned" else "Watching", style = Type.Section, color = Shu.Paper)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (s.items.size == 1) "1 show" else "${s.items.size} shows",
                                    style = Type.Meta, color = Shu.Ash, modifier = Modifier.padding(bottom = 3.dp),
                                )
                            }
                        }
                        itemsIndexed(s.items, key = { _, c -> c.id }) { i, c ->
                            Poster(c, on = i == focusedIdx) {
                                if (i == focusedIdx && pending == null && s.view == "grid") vm.select() else vm.focus(i)
                            }
                        }
                    }
                    // A soft edge where the grid slides under the bar.
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Shu.Ink))),
                    )
                }
            }
        }

        // One bar, one job: what Play will do — or what's playing right now.
        val p = s.playing
        AnimatedContent(
            targetState = when {
                p != null && p.live -> "playing"
                s.view == "sequel" && s.sequel != null && focused != null -> "sequel"
                s.view == "grid" && focused != null -> "next"
                else -> "none"
            },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "bar",
        ) { bar ->
            when (bar) {
                "playing" -> s.playing?.let { MiniPlayer(it, vm, onExpandPlayer) }
                "sequel" -> focused?.let { SequelBar(s, it, vm) }
                "next" -> focused?.let {
                    UpNext(it, vm) { scope.launch { gridState.animateScrollToItem(headerCount + focusedIdx) } }
                }
                else -> Spacer(Modifier.height(0.dp))
            }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

// --- Posters --------------------------------------------------------------------- //

@Composable
private fun Poster(c: Card, on: Boolean, onTap: () -> Unit) {
    val haptics = rememberHaptics()
    val scale by animateFloatAsState(if (on) 1f else 0.97f, tween(200), label = "poster")
    Column(
        Modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .clickable { haptics.tick(); onTap() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .then(if (on) Modifier.border(2.5.dp, Shu.Vermilion, RoundedCornerShape(16.dp)) else Modifier),
        ) {
            Cover(c.cover, c.color, Modifier.fillMaxSize(), RoundedCornerShape(16.dp))
            Box(
                Modifier.fillMaxWidth().height(64.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))),
            )
            Text(
                when {
                    c.caughtUp -> "Caught up"
                    c.progress == 0 -> "New"
                    else -> "Ep ${c.progress + 1}"
                },
                style = Type.Small.copy(fontWeight = FontWeight.Bold),
                color = if (c.caughtUp) Shu.Jade else Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, bottom = 11.dp),
            )
            ProgressLine(
                c.fraction,
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 9.dp, vertical = 6.dp),
                color = if (c.caughtUp) Shu.Jade else Shu.Vermilion,
                track = Color.White.copy(alpha = 0.22f),
                height = 2.5.dp,
            )
            if (on) {
                Box(
                    Modifier.align(Alignment.Center).size(42.dp).clip(CircleShape).background(Shu.Ink.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyph.Play, "Play", Modifier.size(20.dp), tint = Color.White) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            c.title, style = Type.Label.copy(fontSize = 13.5.sp, lineHeight = 17.sp),
            color = if (on) Shu.Paper else Shu.Paper.copy(alpha = 0.82f),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

// --- Continue watching ------------------------------------------------------------ //

@Composable
private fun ContinueWatching(history: List<ResumeEntry>, vm: RemoteViewModel) {
    Column(Modifier.padding(top = 6.dp)) {
        Text("Continue watching", style = Type.Section, color = Shu.Paper)
        Spacer(Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // Bleed to the screen edges while the first card lines up with the grid.
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth().bleed(16.dp),
        ) {
            items(history, key = { "${it.mediaId}:${it.episode}" }) { e -> ResumeCard(e, vm) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** Widen a child past its parent's side padding so a rail can scroll edge to edge. */
private fun Modifier.bleed(side: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = (side * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumeCard(e: ResumeEntry, vm: RemoteViewModel) {
    val haptics = rememberHaptics()
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(width = 264.dp, height = 148.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(showColor(e.color).copy(alpha = 0.6f))
                .combinedClickable(
                    onClick = { haptics.confirm(); vm.resume(e) },
                    onLongClick = { haptics.tick(); menu = true },
                    onClickLabel = "Resume",
                    onLongClickLabel = "More",
                ),
        ) {
            val art = e.banner.ifBlank { e.cover }
            if (art.isNotBlank()) {
                AsyncImage(
                    model = remember(art) { ImageRequest.Builder(ctx).data(art).crossfade(260).build() },
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            // Play sits in the corner, off the characters' faces.
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Play, null, Modifier.size(18.dp), tint = Color.White) }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
                Text(e.title, style = Type.BodyStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Episode ${e.episode}" + if (e.duration > 0) ", ${clock(e.duration - e.position)} left" else "",
                    style = Type.Small, color = Color.White.copy(alpha = 0.78f),
                )
                Spacer(Modifier.height(8.dp))
                ProgressLine(e.fraction, Modifier.fillMaxWidth(), track = Color.White.copy(alpha = 0.25f), height = 3.dp)
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Shu.Booth2)) {
            DropdownMenuItem(
                text = { Text("Remove from Continue watching", style = Type.Label) },
                onClick = { menu = false; vm.forget(e) },
            )
        }
    }
}

// --- The bottom bar ----------------------------------------------------------------- //

@Composable
private fun BarShell(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Shu.Booth2)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
    ) { content() }
}

/** What Play will do: the show the PC is on, and its next episode. */
@Composable
private fun UpNext(c: Card, vm: RemoteViewModel, onReveal: () -> Unit) {
    BarShell(onReveal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(c.cover, c.color, Modifier.size(width = 44.dp, height = 62.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = Type.BodyStrong, color = Shu.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        c.caughtUp -> "Caught up, ${c.progress} watched"
                        c.total != null -> "Episode ${c.progress + 1} of ${c.total}"
                        else -> "Episode ${c.progress + 1}"
                    },
                    style = Type.Meta, color = if (c.caughtUp) Shu.Jade else Shu.Ash,
                )
            }
            Spacer(Modifier.width(10.dp))
            PrimaryButton(if (c.caughtUp) "Continue" else "Play", vm::select, icon = Glyph.Play, height = 50.dp)
        }
    }
}

@Composable
private fun SequelBar(s: KioskState, c: Card, vm: RemoteViewModel) {
    val sq = s.sequel ?: return
    BarShell(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(c.cover, c.color, Modifier.size(width = 44.dp, height = 62.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sq.sequelTitle, style = Type.BodyStrong, color = Shu.Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("You finished ${sq.finished}", style = Type.Meta, color = Shu.Jade, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            GhostButton("Not now", vm::back, height = 50.dp, color = Color.Transparent, tint = Shu.Ash)
            PrimaryButton("Watch", vm::select, icon = Glyph.Play, height = 50.dp)
        }
    }
}

/** Something's playing on the PC: a pocket version of the player. Tap to open it. */
@Composable
fun MiniPlayer(p: Playing, vm: RemoteViewModel, onExpand: () -> Unit) {
    val haptics = rememberHaptics()
    BarShell(onExpand) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(p.cover, p.color, Modifier.size(width = 44.dp, height = 62.dp), RoundedCornerShape(10.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = Type.BodyStrong, color = Shu.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Episode ${p.episode}, ${clock(p.duration - p.position)} left", style = Type.Meta, color = Shu.Ash)
                }
                Pressable(
                    onClick = { haptics.confirm(); vm.pause() },
                    shape = CircleShape, color = Shu.Vermilion,
                    contentDescription = if (p.paused) "Play" else "Pause",
                    modifier = Modifier.size(50.dp),
                ) { Icon(if (p.paused) Glyph.Play else Glyph.Pause, null, Modifier.size(22.dp), tint = Color.White) }
            }
            Spacer(Modifier.height(8.dp))
            ProgressLine((p.position / p.duration).toFloat(), Modifier.fillMaxWidth(), height = 2.5.dp)
        }
    }
}

// --- States without a list ----------------------------------------------------------- //

@Composable
private fun Closed(s: KioskState, pcName: String, vm: RemoteViewModel) {
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (s.history.isNotEmpty()) Box(Modifier.padding(horizontal = 16.dp)) { ContinueWatching(s.history, vm) }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(132.dp).clip(CircleShape).background(Shu.Vermilion.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Pressable(
                    onClick = { haptics.confirm(); vm.open() },
                    shape = CircleShape, color = Shu.Vermilion,
                    contentDescription = "Open Shou on the PC",
                    modifier = Modifier.size(96.dp),
                ) { Icon(Glyph.Power, null, Modifier.size(38.dp), tint = Color.White) }
            }
            Spacer(Modifier.height(26.dp))
            Text("Open Shou", style = Type.Title, color = Shu.Paper)
            Spacer(Modifier.height(8.dp))
            Text(
                "Puts your list on ${pcName.ifBlank { "the PC" }}'s screen and here.",
                style = Type.Body, color = Shu.Ash, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Skeleton(s: KioskState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(s.message.ifBlank { "Loading your list…" }, style = Type.Meta, color = Shu.Ash, modifier = Modifier.padding(vertical = 10.dp))
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                repeat(3) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)).background(Shu.Booth))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(CircleShape).background(Shu.Booth))
                    }
                }
            }
        }
    }
}

@Composable
private fun ListError(s: KioskState, vm: RemoteViewModel) {
    val ctx = LocalContext.current
    val a = s.account
    when {
        a?.listsElsewhere == true -> Message("Couldn't load your list", s.message, "Show ${a.name}'s lists", vm::useAccountLists)
        a?.signedIn == false -> Message("Couldn't load your list", s.message, "Sign in to AniList") { SignInActivity.start(ctx) }
        else -> Message("Couldn't load your list", s.message, "Try again") { vm.showList(s.list) }
    }
}

@Composable
private fun Message(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = Type.Title, color = Shu.Paper, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(body, style = Type.Body, color = Shu.Ash, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(action, onAction, Modifier.widthIn(min = 200.dp))
    }
}

@Composable
private fun Notice(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Shu.Vermilion.copy(alpha = 0.12f)).padding(16.dp),
    ) {
        Text(title, style = Type.BodyStrong, color = Shu.Paper)
        Spacer(Modifier.height(4.dp))
        Text(body, style = Type.Meta, color = Shu.Ash)
        Spacer(Modifier.height(12.dp))
        GhostButton(action, onAction, icon = Glyph.Undo, height = 42.dp)
    }
}
