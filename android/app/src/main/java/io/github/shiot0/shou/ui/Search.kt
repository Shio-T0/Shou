package io.github.shiot0.shou.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shiot0.shou.KioskState
import io.github.shiot0.shou.ListStatus
import io.github.shiot0.shou.RemoteViewModel
import io.github.shiot0.shou.SearchResult

/**
 * Search all of AniList with the phone's own keyboard. The PC's kiosk mirrors every
 * keystroke and highlights the same result, and tapping a result opens it on both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Search(s: KioskState, vm: RemoteViewModel) {
    val search = s.search ?: return
    var showFilters by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            QueryField(search.query, vm, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            FilterButton(search.genres.size) { showFilters = true }
        }
        if (search.genres.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                items(search.genres) { g -> ActiveFilter(g) { vm.toggleGenre(g) } }
            }
        }
        Results(s, vm, Modifier.weight(1f))
    }

    if (showFilters) {
        ModalBottomSheet(
            onDismissRequest = { showFilters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Shu.Booth,
            contentColor = Shu.Paper,
        ) { Filters(s, vm) }
    }
}

@Composable
private fun QueryField(server: String, vm: RemoteViewModel, modifier: Modifier) {
    var field by remember { mutableStateOf(TextFieldValue(server, TextRange(server.length))) }
    val focus = LocalFocusManager.current
    // Someone typed on the PC, or the search was reset: take the server's text.
    LaunchedEffect(server) {
        if (server != field.text && vm.adoptServerQuery(server)) field = TextFieldValue(server, TextRange(server.length))
    }
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Shu.Booth2)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.Search, null, Modifier.size(20.dp), tint = Shu.Ash)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (field.text.isEmpty()) Text("Search AniList", style = Type.Body, color = Shu.Ash)
            BasicTextField(
                value = field,
                onValueChange = { v ->
                    val changed = v.text != field.text
                    field = v
                    if (changed) vm.editQuery(v.text)
                },
                singleLine = true,
                textStyle = Type.Body.copy(color = Shu.Paper, fontSize = 16.sp),
                cursorBrush = SolidColor(Shu.Vermilion),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (field.text.isNotEmpty()) {
            RoundButton(
                Glyph.Close, "Clear search", {
                    field = TextFieldValue("")
                    vm.clearQuery()
                },
                size = 40.dp, iconSize = 16.dp, color = Color.Transparent, border = false, tint = Shu.Ash,
            )
        }
    }
}

@Composable
private fun FilterButton(count: Int, onClick: () -> Unit) {
    Box {
        RoundButton(
            Glyph.Filter, "Filter by genre", onClick,
            size = 52.dp, iconSize = 21.dp,
            color = if (count > 0) Shu.Vermilion.copy(alpha = 0.16f) else Shu.Booth,
            tint = if (count > 0) Shu.Vermilion else Shu.Paper,
        )
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(20.dp).clip(CircleShape).background(Shu.Vermilion),
                contentAlignment = Alignment.Center,
            ) { Text("$count", style = Type.Small.copy(fontSize = 11.sp), color = Color.White) }
        }
    }
}

@Composable
private fun ActiveFilter(label: String, onRemove: () -> Unit) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.tick(); onRemove() },
        shape = CircleShape,
        color = Shu.Vermilion.copy(alpha = 0.14f),
        contentDescription = "Remove $label filter",
        modifier = Modifier.height(34.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.Meta, color = Shu.Paper)
            Spacer(Modifier.width(6.dp))
            Icon(Glyph.Close, null, Modifier.size(12.dp), tint = Shu.Vermilion)
        }
    }
}

@Composable
private fun Results(s: KioskState, vm: RemoteViewModel, modifier: Modifier) {
    val search = s.search ?: return
    val listState = rememberLazyListState()
    val results = search.results

    // A new query or filter replaces the list; start it from the top. (LazyColumn would
    // otherwise keep the previous first row anchored and hide the new top results.)
    LaunchedEffect(results.firstOrNull()?.id) { listState.scrollToItem(0) }
    // The PC's own keyboard moves the highlight too; keep it in view.
    LaunchedEffect(search.cursor) {
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (search.cursor !in visible && search.cursor < results.size) listState.animateScrollToItem(search.cursor)
    }
    // Infinite scroll: ask for the next page a few rows before the end.
    val nearEnd by remember(results.size) {
        derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= results.size - 4 }
    }
    LaunchedEffect(nearEnd, search.hasMore) { if (nearEnd && search.hasMore) vm.loadMore() }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(results, key = { _, r -> r.id }) { i, r ->
                ResultRow(r, focused = i == search.cursor) { vm.pick(i) }
            }
            if (search.hasMore && results.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Shu.Ash, strokeWidth = 2.dp)
                    }
                }
            }
        }
        if (results.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (search.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Shu.Vermilion, strokeWidth = 2.dp)
                } else {
                    Text(
                        if (search.query.isBlank()) "Type a title to search all of AniList"
                        else "Nothing on AniList matches “${search.query}”",
                        style = Type.Body, color = Shu.Ash, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: SearchResult, focused: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.tick(); onClick() },
        shape = RoundedCornerShape(22.dp),
        color = if (focused) Shu.Booth3 else Shu.Booth,
        // The PC's highlighted result keeps a soft vermilion ring, so you can see what Enter picks.
        border = if (focused) BorderStroke(1.5.dp, Shu.Vermilion.copy(alpha = 0.55f)) else null,
        modifier = Modifier.fillMaxWidth().height(88.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(r.cover, r.color, Modifier.size(width = 48.dp, height = 68.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = Type.BodyStrong, color = Shu.Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(resultMeta(r), style = Type.Small, color = Shu.Ash, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            val st = r.listStatus
            if (st != null) {
                StatusPill(ListStatus.label[st] ?: st, statusColor(st))
            } else {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Shu.Booth2),
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyph.Plus, "Not in your lists", Modifier.size(15.dp), tint = Shu.Ash) }
            }
        }
    }
}

fun resultMeta(r: SearchResult): String = listOfNotNull(
    r.format.ifBlank { null },
    r.year?.toString(),
    r.episodes?.let { if (it == 1) "1 episode" else "$it episodes" },
).joinToString(", ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(s: KioskState, vm: RemoteViewModel) {
    val search = s.search ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filters", style = Type.TitleSmall, color = Shu.Paper)
            Spacer(Modifier.weight(1f))
            if (search.genres.isNotEmpty()) GhostButton("Clear all", vm::clearGenres, height = 38.dp, tint = Shu.Vermilion)
        }
        Spacer(Modifier.height(6.dp))
        Text("Every filter you pick narrows the results.", style = Type.Meta, color = Shu.Ash)
        for ((label, options) in listOf("Genres" to search.genreList, "Themes" to search.tagList)) {
            if (options.isEmpty()) continue
            Spacer(Modifier.height(20.dp))
            Text(label, style = Type.Heading, color = Shu.Paper)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in options) FilterChip(g, g in search.genres) { vm.toggleGenre(g) }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Pressable(
        onClick = { haptics.tick(); onClick() },
        shape = CircleShape,
        color = if (on) Shu.Vermilion else Shu.Booth2,
        modifier = Modifier.height(38.dp),
    ) {
        Text(label, style = Type.Meta, color = if (on) Color.White else Shu.Paper, modifier = Modifier.padding(horizontal = 14.dp))
    }
}
