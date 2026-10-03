package io.github.shiot0.shou.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiot0.shou.KioskState
import io.github.shiot0.shou.Link
import io.github.shiot0.shou.Remote
import io.github.shiot0.shou.RemoteViewModel
import io.github.shiot0.shou.SignInActivity

/** Which layout the remote shows, following what the PC's kiosk is doing. */
enum class Mode { BROWSE, PLAYER, RATING, SEARCH, DETAIL }

fun modeOf(s: KioskState): Mode = when (s.view) {
    "search" -> Mode.SEARCH
    "detail" -> Mode.DETAIL
    "rating" -> if (s.rating != null) Mode.RATING else Mode.BROWSE
    "playing" -> if (s.playing != null) Mode.PLAYER else Mode.BROWSE
    else -> Mode.BROWSE
}

@Composable
fun ShouApp(vm: RemoteViewModel, onSettings: () -> Unit, onLeave: () -> Unit) {
    val remotes by vm.remotes.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    if (remotes.isEmpty() && remote == null) {
        Welcome(vm)
    } else {
        Home(vm, onSettings, onLeave)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Home(vm: RemoteViewModel, onSettings: () -> Unit, onLeave: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    val pending by vm.pendingFocus.collectAsStateWithLifecycle()
    var showServers by rememberSaveable { mutableStateOf(false) }
    // The full player opened from the mini player (while the PC shows the list).
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.notices.collect { snack.currentSnackbarDismiss(); snack.showSnackbar(it) } }

    val state = s
    val kioskMode = state?.let(::modeOf)
    if (state?.playing == null) playerOpen = false
    val mode = if (playerOpen && kioskMode == Mode.BROWSE) Mode.PLAYER else kioskMode
    val (art, tint) = ambientFor(state, mode, pending)

    BackHandler {
        when {
            playerOpen -> playerOpen = false
            mode == Mode.DETAIL || mode == Mode.SEARCH -> vm.back()
            else -> onLeave()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Ambient(art, tint)
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            TopBar(remote, link, onServers = { showServers = true }, onSettings = onSettings, onOpen = vm::open)
            AnimatedVisibility(
                visible = state != null && link == Link.OFFLINE,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) { ReconnectBanner(remote?.name.orEmpty()) }
            val account = state?.account
            AnimatedVisibility(
                visible = account != null && !account.signedIn && link == Link.LIVE &&
                    (mode == Mode.BROWSE || mode == Mode.SEARCH),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) { SignInBanner(expired = account?.expired == true) }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state == null && link == Link.OFFLINE -> Unreachable(vm, remote, onServers = { showServers = true })
                    state == null -> Connecting(remote?.name.orEmpty())
                    else -> AnimatedContent(
                        targetState = mode!!,
                        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                        label = "mode",
                    ) { m ->
                        when (m) {
                            Mode.BROWSE -> Browse(state, pending, remote?.name.orEmpty(), vm) { playerOpen = true }
                            Mode.PLAYER -> Player(state, vm, onCollapse = if (playerOpen) ({ playerOpen = false }) else null)
                            Mode.RATING -> RatingPanel(state, vm)
                            Mode.SEARCH -> Search(state, vm)
                            Mode.DETAIL -> ShowDetail(state, vm)
                        }
                    }
                }
            }

            // The tabs stay put above the keyboard, so Watching/Planned are always one tap
            // away — even mid-search.
            val tabs = state != null && (mode == Mode.BROWSE || mode == Mode.SEARCH)
            if (tabs) ListTabs(state!!.list, onPick = vm::showList)
            else Spacer(Modifier.navigationBarsPadding())
        }
        SnackbarHost(
            snack,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 150.dp),
        ) { data ->
            Snackbar(data, containerColor = Shu.Booth2, contentColor = Shu.Paper, shape = RoundedCornerShape(16.dp))
        }
    }

    if (showServers) ServersSheet(vm, onDismiss = { showServers = false })
}

private fun SnackbarHostState.currentSnackbarDismiss() = currentSnackbarData?.dismiss()

/** The art + colour that light the screen for the current mode. */
private fun ambientFor(s: KioskState?, mode: Mode?, pending: Int?): Pair<String, Int?> {
    if (s == null) return "" to null
    return when (mode) {
        Mode.PLAYER -> s.playing?.let { it.cover to it.color } ?: ("" to null)
        Mode.RATING -> s.rating?.let { it.cover to it.color } ?: ("" to null)
        Mode.DETAIL -> s.search?.detail?.let { it.cover to it.color } ?: ("" to null)
        Mode.SEARCH -> "" to null
        else -> s.items.getOrNull(pending ?: s.cursor)?.let { it.cover to it.color } ?: ("" to null)
    }
}

@Composable
private fun TopBar(remote: Remote?, link: Link, onServers: () -> Unit, onSettings: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pressable(
            onClick = onServers,
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            contentDescription = "Switch PC",
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Row(Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Seal(size = 38.dp)
                Spacer(Modifier.width(11.dp))
                Column {
                    Text(
                        remote?.name?.ifBlank { null } ?: "Shou",
                        style = Type.Heading,
                        color = Shu.Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                    LinkLabel(link)
                }
                Spacer(Modifier.width(4.dp))
                Icon(Glyph.ChevronDown, null, Modifier.size(16.dp), tint = Shu.Ash)
            }
        }
        Spacer(Modifier.weight(1f))
        if (link == Link.LIVE) {
            RoundButton(
                Glyph.Power, "Open Shou on the PC", onOpen,
                size = 44.dp, iconSize = 21.dp, color = Color.Transparent, tint = Shu.Ash,
            )
        }
        RoundButton(
            Glyph.Tune, "Settings", onSettings,
            size = 44.dp, iconSize = 22.dp, color = Color.Transparent, tint = Shu.Ash,
        )
    }
}

@Composable
private fun LinkLabel(link: Link) {
    val (label, color) = when (link) {
        Link.LIVE -> "Connected" to Shu.Jade
        Link.CONNECTING -> "Connecting" to Shu.Ash
        Link.OFFLINE -> "Offline" to Shu.Vermilion
        Link.NONE -> "Not connected" to Shu.Ash
    }
    val c by animateColorAsState(color, tween(400), label = "link")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.Small, color = c)
    }
}

@Composable
private fun ReconnectBanner(name: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Shu.Vermilion.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = Shu.Vermilion, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("Lost ${name.ifBlank { "the PC" }}. Reconnecting…", style = Type.Meta, color = Shu.Paper)
    }
}

/** Without an AniList sign-in the PC can't even load your lists any more — say so, with the fix. */
@Composable
private fun SignInBanner(expired: Boolean) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Shu.Booth2)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (expired) "AniList sign-in expired" else "Not signed in to AniList", style = Type.Label, color = Shu.Paper)
            Text("Sign in so Shou can load your lists and track episodes.", style = Type.Small, color = Shu.Ash)
        }
        Spacer(Modifier.width(10.dp))
        PrimaryButton("Sign in", { SignInActivity.start(ctx) }, height = 40.dp)
    }
}

@Composable
private fun Connecting(name: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), color = Shu.Vermilion, strokeWidth = 2.5.dp)
        Spacer(Modifier.height(16.dp))
        Text("Connecting to ${name.ifBlank { "your PC" }}…", style = Type.Meta, color = Shu.Ash)
    }
}

@Composable
private fun Unreachable(vm: RemoteViewModel, remote: Remote?, onServers: () -> Unit) {
    var waking by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Seal(size = 64.dp, lit = false)
        Spacer(Modifier.height(22.dp))
        Text(
            "Can't reach ${remote?.name?.ifBlank { null } ?: "your PC"}",
            style = Type.Title, color = Shu.Paper, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Check that the PC is on and running Shou, and that this phone is on the same Wi-Fi. " +
                "Shou keeps trying in the background.",
            style = Type.Body, color = Shu.Ash, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (remote != null && remote.mac.isNotBlank()) {
            PrimaryButton(
                waking ?: "Wake the PC", onClick = {
                    vm.wake(remote) { ok -> waking = if (ok) "Wake signal sent" else "Couldn't send the wake signal" }
                },
                icon = Glyph.Bolt, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Try again", vm::retry, Modifier.fillMaxWidth())
        } else {
            PrimaryButton("Try again", vm::retry, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        GhostButton("Switch PC", onServers, Modifier.fillMaxWidth())
    }
}

/** Watching · Planned · Search, at the bottom where thumbs are (and above the keyboard). */
@Composable
private fun ListTabs(current: String, onPick: (String) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Shu.Ink.copy(alpha = 0.97f))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        for ((key, label, icon) in listOf(
            Triple("watching", "Watching", Glyph.Play),
            Triple("planned", "Planned", Glyph.Bookmark),
            Triple("search", "Search", Glyph.Search),
        )) {
            val on = current == key
            val pill by animateColorAsState(if (on) Shu.Vermilion.copy(alpha = 0.16f) else Color.Transparent, tween(250), label = "tab-pill")
            val fg by animateColorAsState(if (on) Shu.Vermilion else Shu.Ash, tween(250), label = "tab-fg")
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { if (!on) { haptics.tick(); onPick(key) } }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(width = 60.dp, height = 32.dp).clip(CircleShape).background(pill),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, Modifier.size(20.dp), tint = fg) }
                Spacer(Modifier.height(4.dp))
                Text(label, style = Type.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = if (on) Shu.Paper else Shu.Ash)
            }
        }
    }
}
