package io.github.shiot0.shou.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiot0.shou.Link
import io.github.shiot0.shou.NsdResult
import io.github.shiot0.shou.Remote
import io.github.shiot0.shou.RemoteViewModel

/** Every Shou PC this phone knows: switch, add, rename, wake, forget. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersSheet(vm: RemoteViewModel, onDismiss: () -> Unit) {
    val remotes by vm.remotes.collectAsStateWithLifecycle()
    val active by vm.remote.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Remote?>(null) }
    var adding by remember { mutableStateOf<NsdResult?>(null) }
    var addingBlank by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Remote?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Shu.Booth,
        contentColor = Shu.Paper,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            Text("Your Shou PCs", style = Type.TitleSmall, color = Shu.Paper)
            Spacer(Modifier.height(4.dp))
            Text("Each one keeps its key, and Shou finds it again on whatever network you're on.", style = Type.Meta, color = Shu.Ash)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in remotes) {
                    val on = r.key == active?.key
                    RemoteRow(
                        r, on, if (on) link else null,
                        onPick = { vm.switchTo(r); onDismiss() },
                        onEdit = { editing = r },
                        onDelete = { confirmDelete = r },
                        vm = vm,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Discover(vm, onUse = { adding = it })
            Spacer(Modifier.height(10.dp))
            GhostButton("Add a PC by address", { addingBlank = true }, Modifier.fillMaxWidth(), icon = Glyph.Plus)
        }
    }

    val formFor = editing
    if (formFor != null || adding != null || addingBlank) {
        RemoteForm(
            initial = formFor,
            found = adding,
            onSave = { vm.saveRemote(it); editing = null; adding = null; addingBlank = false; onDismiss() },
            onCancel = { editing = null; adding = null; addingBlank = false },
        )
    }
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Shu.Booth2,
            title = { Text("Forget ${r.name.ifBlank { "this PC" }}?", style = Type.Heading) },
            text = { Text("Its key is removed from this phone. You can add it again any time.", style = Type.Body, color = Shu.Ash) },
            confirmButton = { TextButton({ vm.deleteRemote(r); confirmDelete = null }) { Text("Forget", color = Shu.Rose, style = Type.Label) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Keep", color = Shu.Paper, style = Type.Label) } },
        )
    }
}

@Composable
private fun RemoteRow(
    r: Remote,
    active: Boolean,
    link: Link?,
    onPick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    vm: RemoteViewModel,
) {
    var menu by remember { mutableStateOf(false) }
    var wakeNote by remember { mutableStateOf<String?>(null) }
    Pressable(
        onClick = onPick,
        shape = RoundedCornerShape(18.dp),
        color = if (active) Shu.Booth2 else Shu.Ink.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, if (active) Shu.Vermilion.copy(alpha = 0.45f) else Shu.Rule),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Seal(size = 42.dp, lit = active)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name.ifBlank { r.bestHost() }, style = Type.BodyStrong, color = Shu.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    wakeNote ?: when (link) {
                        Link.LIVE -> "Connected, ${r.bestHost()}"
                        Link.CONNECTING -> "Connecting to ${r.bestHost()}…"
                        Link.OFFLINE -> "Can't reach ${r.bestHost()}"
                        else -> "${r.bestHost()}:${r.port}"
                    },
                    style = Type.Small,
                    color = when {
                        wakeNote != null -> Shu.Paper
                        link == Link.LIVE -> Shu.Jade
                        link == Link.OFFLINE -> Shu.Vermilion
                        else -> Shu.Ash
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                RoundButton(Glyph.More, "More for ${r.name}", { menu = true }, size = 44.dp, iconSize = 20.dp, color = Color.Transparent, border = false, tint = Shu.Ash)
                DropdownMenu(menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Shu.Booth2)) {
                    if (r.mac.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("Wake the PC", style = Type.Label) },
                            leadingIcon = { Icon(Glyph.Bolt, null, Modifier.size(18.dp), tint = Shu.Vermilion) },
                            onClick = {
                                menu = false
                                vm.wake(r) { ok -> wakeNote = if (ok) "Wake signal sent" else "Couldn't send the wake signal" }
                            },
                        )
                    }
                    DropdownMenuItem(text = { Text("Edit", style = Type.Label) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Forget", style = Type.Label, color = Shu.Rose) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** mDNS scan for `_shou._tcp` — the PC's address without typing an IP. */
@Composable
private fun Discover(vm: RemoteViewModel, primary: Boolean = false, onUse: (NsdResult) -> Unit) {
    val found by vm.found.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    var scanned by remember { mutableStateOf(false) }
    val label = when {
        scanning -> "Looking on this network…"
        scanned && found.isEmpty() -> "No PCs found. Look again"
        else -> "Find PCs on this network"
    }
    val scan = { scanned = true; vm.scan() }
    if (primary) {
        PrimaryButton(label, scan, Modifier.fillMaxWidth(), icon = Glyph.Signal, enabled = !scanning)
    } else {
        GhostButton(label, scan, Modifier.fillMaxWidth(), icon = Glyph.Signal, tint = Shu.Vermilion, enabled = !scanning)
    }
    if (found.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in found) {
                Pressable(
                    onClick = { onUse(f) },
                    shape = RoundedCornerShape(16.dp),
                    color = Shu.Ink.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, Shu.Rule),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyph.Monitor, null, Modifier.size(22.dp), tint = Shu.Vermilion)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = Type.BodyStrong, color = Shu.Paper)
                            Text("${f.host}:${f.port}", style = Type.Small, color = Shu.Ash)
                        }
                        Text("Add", style = Type.Label, color = Shu.Vermilion)
                    }
                }
            }
        }
    }
}

/** Parsed bits of the phone link install.sh prints: http://host:4100/remote?k=TOKEN */
private data class PhoneLink(val host: String, val port: String, val key: String)

private fun parsePhoneLink(text: String): PhoneLink? {
    val t = text.trim()
    if (!t.contains("://") || !t.contains("k=")) return null
    return runCatching {
        val u = Uri.parse(t)
        val key = u.getQueryParameter("k").orEmpty()
        val host = u.host.orEmpty()
        if (key.isBlank() || host.isBlank()) null
        else PhoneLink(host, if (u.port > 0) u.port.toString() else "4100", key)
    }.getOrNull()
}

/** Add or edit a PC. Pasting the whole phone link into Address fills in everything. */
@Composable
fun RemoteForm(initial: Remote?, found: NsdResult?, onSave: (Remote) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: found?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.let { it.host.ifBlank { it.hostname } } ?: found?.host.orEmpty()) }
    var port by remember { mutableStateOf(initial?.port ?: found?.port?.toString() ?: "4100") }
    var key by remember { mutableStateOf(initial?.key.orEmpty()) }
    var mac by remember { mutableStateOf(initial?.mac.orEmpty()) }
    var tried by remember { mutableStateOf(false) }

    fun absorbLink(text: String): Boolean {
        val l = parsePhoneLink(text) ?: return false
        host = l.host; port = l.port; key = l.key
        if (name.isBlank()) name = l.host.substringBefore(".local")
        return true
    }
    // Fill in from a link the moment one is pasted (several characters arriving at once);
    // a link typed out by hand is picked apart on Save instead, once it's complete.
    fun pasted(old: String, new: String) = new.length - old.length > 1 && absorbLink(new)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Shu.Ink)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Glyph.Back, "Cancel", onCancel, size = 44.dp, color = Color.Transparent, border = false)
                Spacer(Modifier.width(6.dp))
                Text(if (initial == null) "Add a PC" else "Edit ${initial.name}", style = Type.TitleSmall, color = Shu.Paper)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Paste the phone link that install.sh printed into Address and the rest fills itself in.",
                style = Type.Meta, color = Shu.Ash,
            )
            Spacer(Modifier.height(18.dp))
            Field("Name", name, { name = it }, placeholder = "Living room")
            Field(
                "Address", host, { if (!pasted(host, it)) host = it.trim() },
                placeholder = "192.168.1.20 or my-pc.local", keyboard = KeyboardType.Uri,
                error = if (tried && host.isBlank()) "Enter the PC's address or paste its phone link" else null,
            )
            Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, placeholder = "4100", keyboard = KeyboardType.Number)
            Field(
                "Key", key, { if (!pasted(key, it)) key = it.trim() },
                placeholder = "REMOTE_TOKEN",
                help = "REMOTE_TOKEN in ~/.config/shou/shou.conf on the PC",
                error = if (tried && key.isBlank()) "The key is required" else null,
            )
            Field(
                "MAC address (optional)", mac, { mac = it.trim().uppercase().take(17) },
                placeholder = "AA:BB:CC:DD:EE:FF",
                help = "Lets this phone wake the PC from sleep (Wake-on-LAN)",
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                if (initial == null) "Save and connect" else "Save",
                onClick = {
                    tried = true
                    absorbLink(host) || absorbLink(key)
                    if (host.isNotBlank() && key.isNotBlank()) {
                        onSave(
                            Remote(
                                id = initial?.id ?: RemoteViewModel.newId(),
                                name = name.trim().ifBlank { host.substringBefore(".local") },
                                key = key,
                                host = host,
                                hostname = initial?.hostname.orEmpty(),
                                port = port.ifBlank { "4100" },
                                mac = mac,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    help: String? = null,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = Type.Meta) },
        placeholder = { Text(placeholder, style = Type.Body, color = Shu.Ash.copy(alpha = 0.6f)) },
        supportingText = (error ?: help)?.let { { Text(it, style = Type.Small) } },
        isError = error != null,
        singleLine = true,
        textStyle = Type.Body.copy(color = Shu.Paper),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            keyboardType = keyboard,
        ),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Shu.Vermilion,
            unfocusedBorderColor = Shu.Rule,
            focusedLabelColor = Shu.Vermilion,
            unfocusedLabelColor = Shu.Ash,
            cursorColor = Shu.Vermilion,
            focusedSupportingTextColor = Shu.Ash,
            unfocusedSupportingTextColor = Shu.Ash,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

/** First run: find the PC (or type it in), then you're in. */
@Composable
fun Welcome(vm: RemoteViewModel) {
    var form by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<NsdResult?>(null) }
    Box(Modifier.fillMaxSize()) {
        Ambient("", null)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Seal(size = 76.dp)
            Spacer(Modifier.height(22.dp))
            Text("Shou", style = Type.Display.copy(fontSize = 44.sp, lineHeight = 50.sp), color = Shu.Paper)
            Spacer(Modifier.height(10.dp))
            Text(
                "Your anime list on the big screen, and this phone as the remote. First, find the PC running Shou.",
                style = Type.Body, color = Shu.Ash, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1.2f))
            Discover(vm, primary = true, onUse = { picked = it; form = true })
            Spacer(Modifier.height(10.dp))
            GhostButton("Enter the address myself", { picked = null; form = true }, Modifier.fillMaxWidth(), icon = Glyph.Plus)
            Spacer(Modifier.height(28.dp))
        }
    }
    if (form) {
        RemoteForm(
            initial = null, found = picked,
            onSave = { vm.saveRemote(it); form = false },
            onCancel = { form = false },
        )
    }
}
