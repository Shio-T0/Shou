package io.github.shiot0.shou.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shiot0.shou.Detail
import io.github.shiot0.shou.KioskState
import io.github.shiot0.shou.ListStatus
import io.github.shiot0.shou.RemoteViewModel
import io.github.shiot0.shou.SignInActivity

/** One show from Search: what it is, its other seasons, and which of your lists it's on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShowDetail(s: KioskState, vm: RemoteViewModel) {
    val search = s.search ?: return
    val d = search.detail ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        GhostButton("Results", vm::back, icon = Glyph.Back, height = 40.dp, tint = Shu.Ash)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Cover(
                d.cover, d.color,
                Modifier.size(width = 116.dp, height = 166.dp)
                    .shadow(22.dp, RoundedCornerShape(12.dp), ambientColor = showColor(d.color), spotColor = showColor(d.color)),
                RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, style = if (d.title.length > 34) Type.TitleSmall else Type.Title, color = Shu.Paper, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(detailMeta(d), style = Type.Meta, color = Shu.Ash)
                if (d.score != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("${d.score}% on AniList", style = Type.Meta, color = Shu.Paper)
                }
            }
        }

        if (d.genres.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (g in d.genres) {
                    Text(
                        g, style = Type.Small, color = Shu.Paper,
                        modifier = Modifier.clip(CircleShape).background(Shu.Booth2).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (d.loading) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(Modifier.size(20.dp), color = Shu.Ash, strokeWidth = 2.dp)
        } else if (d.description.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Synopsis(d.description)
        }

        if (search.seasons.size >= 2) {
            Spacer(Modifier.height(20.dp))
            Seasons(search.seasons.getOrNull(search.seasonIdx)?.title.orEmpty(), search.seasonIdx, search.seasons.size, vm)
        }

        Spacer(Modifier.height(24.dp))
        Text("Your lists", style = Type.Heading, color = Shu.Paper)
        Spacer(Modifier.height(4.dp))
        Text(
            d.listStatus?.let { "On your ${ListStatus.label[it] ?: it} list" } ?: "Not on any of your lists yet",
            style = Type.Meta, color = statusColor(d.listStatus),
        )
        Spacer(Modifier.height(12.dp))
        StatusGrid(d, search.statuses, search.canWrite && !search.busy, vm)
        if (!search.canWrite) {
            val ctx = LocalContext.current
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Sign in to AniList to change your lists from here.",
                    style = Type.Meta, color = Shu.Ash, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                PrimaryButton("Sign in", { SignInActivity.start(ctx) }, height = 40.dp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun detailMeta(d: Detail): String = listOfNotNull(
    d.format.ifBlank { null },
    d.year?.toString(),
    d.episodes?.let { if (it == 1) "1 episode" else "$it episodes" },
    d.studio.ifBlank { null },
).joinToString(", ")

@Composable
private fun Synopsis(text: String) {
    var open by remember(text) { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Text(
            text, style = Type.Body, color = Shu.Paper.copy(alpha = 0.86f),
            maxLines = if (open) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
        )
        if (text.length > 260) {
            Text(
                if (open) "Show less" else "Read more",
                style = Type.Label, color = Shu.Vermilion,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Seasons(title: String, idx: Int, count: Int, vm: RemoteViewModel) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Shu.Booth).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(Glyph.ChevronLeft, "Earlier season", vm::left, size = 48.dp, enabled = idx > 0, border = false, color = Shu.Booth2)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Season ${idx + 1} of $count", style = Type.Label, color = Shu.Paper)
            Text(title, style = Type.Small, color = Shu.Ash, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(Glyph.ChevronRight, "Later season", vm::right, size = 48.dp, enabled = idx < count - 1, border = false, color = Shu.Booth2)
    }
}

@Composable
private fun StatusGrid(d: Detail, statuses: List<Pair<String, String>>, enabled: Boolean, vm: RemoteViewModel) {
    val haptics = rememberHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        statuses.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    val on = d.listStatus == value
                    val c = statusColor(value)
                    Pressable(
                        onClick = { haptics.confirm(); vm.setStatus(value) },
                        enabled = enabled && !on,
                        shape = RoundedCornerShape(20.dp),
                        color = if (on) c.copy(alpha = 0.18f) else Shu.Booth,
                        modifier = Modifier.weight(1f).height(54.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                            Spacer(Modifier.width(10.dp))
                            Text(label, style = Type.Label, color = if (on) c else if (enabled) Shu.Paper else Shu.Ash, modifier = Modifier.weight(1f))
                            if (on) Icon(Glyph.Check, "Current", Modifier.size(18.dp), tint = c)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (d.listStatus != null) {
            Pressable(
                onClick = { haptics.confirm(); vm.setStatus("REMOVE") },
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                color = Shu.Rose.copy(alpha = 0.10f),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Remove from your lists", style = Type.Label, color = if (enabled) Shu.Rose else Shu.Ash) }
        }
    }
}
