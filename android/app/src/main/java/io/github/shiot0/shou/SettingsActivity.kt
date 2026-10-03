package io.github.shiot0.shou

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.shiot0.shou.ui.GhostButton
import io.github.shiot0.shou.ui.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.shiot0.shou.ui.Glyph
import io.github.shiot0.shou.ui.RoundButton
import io.github.shiot0.shou.ui.Shu
import io.github.shiot0.shou.ui.ShouTheme
import io.github.shiot0.shou.ui.Type

/** App-wide options. Your PCs themselves are managed from the switcher on the remote. */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ShouStore.init(this)
        LiveLink.init(this)
        setContent { ShouTheme { SettingsScreen(onBack = ::finish) } }
    }

    @Composable
    private fun SettingsScreen(onBack: () -> Unit) {
        val ctx = this
        var volumeKeys by remember { mutableStateOf(ShouStore.volumeKeys(ctx)) }
        var https by remember { mutableStateOf(ShouStore.https(ctx)) }
        var badCerts by remember { mutableStateOf(ShouStore.allowBadCerts(ctx)) }
        val state by LiveLink.state.collectAsStateWithLifecycle()
        val link by LiveLink.link.collectAsStateWithLifecycle()

        Column(
            Modifier.fillMaxSize().background(Shu.Ink).statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Glyph.Back, "Back", onBack, size = 44.dp, color = Color.Transparent, border = false)
                Spacer(Modifier.width(6.dp))
                Text("Settings", style = Type.TitleSmall, color = Shu.Paper)
            }
            Spacer(Modifier.height(10.dp))

            Section("AniList")
            AccountPanel(state?.account, link == Link.LIVE)

            Section("Controls")
            Toggle(
                "Volume buttons control the PC",
                "The phone's volume keys change the PC player's volume while the remote is open.",
                volumeKeys,
            ) { volumeKeys = it; ShouStore.setOption(ctx, "volumeKeys", it) }

            Section("Connection")
            Toggle(
                "Use HTTPS",
                "Only if you put Shou behind a TLS proxy. Plain HTTP is normal on a home network.",
                https,
            ) { https = it; ShouStore.setOption(ctx, "https", it); LiveLink.reconnect() }
            Toggle(
                "Allow a self-signed certificate",
                "Trust your own server's certificate when using HTTPS.",
                badCerts, enabled = https,
            ) { badCerts = it; ShouStore.setOption(ctx, "allowBadCerts", it); LiveLink.reconnect() }

            Section("About")
            Text(
                "Shou Remote ${BuildConfig.VERSION_NAME}\n" +
                    "Type: Shippori Mincho B1 and Zen Kaku Gothic New, both under the SIL Open Font License 1.1.",
                style = Type.Meta, color = Shu.Ash,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    /** Who the PC is signed in to AniList as, and the ways to change that. */
    @Composable
    private fun AccountPanel(account: Account?, connected: Boolean) {
        val ctx = this
        val scope = rememberCoroutineScope()
        var confirmOut by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf<String?>(null) }

        fun post(path: String) {
            note = null
            scope.launch {
                val (code, reply) = withContext(Dispatchers.IO) { ServerClient.postForm(ctx, path, emptyMap()) }
                if (code !in 200..299) note = reply?.optString("reason")?.ifBlank { null } ?: "Couldn't reach the PC."
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Shu.Booth).padding(16.dp),
        ) {
            when {
                account == null -> Text(
                    if (connected) "Update Shou on the PC to manage its AniList sign-in from here."
                    else "Connect to a Shou PC to manage its AniList sign-in.",
                    style = Type.Meta, color = Shu.Ash,
                )
                account.signedIn -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(CircleShape).background(Shu.Booth2)) {
                            if (account.avatar.isNotBlank()) {
                                AsyncImage(account.avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Signed in as ${account.name}", style = Type.BodyStrong, color = Shu.Paper)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (account.listsElsewhere) "Showing ${account.listUser}'s lists"
                                else "Shou shows your lists and marks episodes watched.",
                                style = Type.Meta, color = if (account.listsElsewhere) Shu.Vermilion else Shu.Ash,
                            )
                        }
                    }
                    if (account.listsElsewhere) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Episodes you finish are marked on ${account.name}'s account, not ${account.listUser}'s. " +
                                "Use one account for both.",
                            style = Type.Meta, color = Shu.Ash,
                        )
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton("Show ${account.name}'s lists", { post("auth/lists") }, Modifier.fillMaxWidth(), height = 48.dp)
                        Spacer(Modifier.height(8.dp))
                        GhostButton(
                            "Sign in as ${account.listUser} instead", { SignInActivity.start(ctx, differentAccount = true) },
                            Modifier.fillMaxWidth(),
                        )
                    } else {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton("Switch account", { SignInActivity.start(ctx, differentAccount = true) }, Modifier.weight(1f))
                            GhostButton("Sign out", { confirmOut = true }, Modifier.weight(1f), tint = Shu.Rose)
                        }
                    }
                }
                else -> {
                    Text(
                        if (account.expired) "Your AniList sign-in expired" else "Not signed in to AniList",
                        style = Type.BodyStrong, color = Shu.Paper,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sign in so Shou can load your lists, mark episodes watched and save your ratings.",
                        style = Type.Meta, color = Shu.Ash,
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Sign in to AniList", { SignInActivity.start(ctx) }, Modifier.fillMaxWidth(), height = 50.dp)
                }
            }
            note?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = Type.Meta, color = Shu.Vermilion)
            }
        }

        if (confirmOut && account != null) {
            AlertDialog(
                onDismissRequest = { confirmOut = false },
                containerColor = Shu.Booth2,
                title = { Text("Sign out of AniList?", style = Type.Heading) },
                text = {
                    Text(
                        "The PC forgets ${account.name}'s sign-in. Shou can't load lists or mark episodes " +
                            "watched until you sign in again.",
                        style = Type.Body, color = Shu.Ash,
                    )
                },
                confirmButton = {
                    TextButton({ confirmOut = false; post("auth/signout") }) { Text("Sign out", color = Shu.Rose, style = Type.Label) }
                },
                dismissButton = { TextButton({ confirmOut = false }) { Text("Cancel", color = Shu.Paper, style = Type.Label) } },
            )
        }
    }

    @Composable
    private fun Section(title: String) {
        Text(title, style = Type.Label, color = Shu.Vermilion, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp))
    }

    @Composable
    private fun Toggle(title: String, body: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(Shu.Booth)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.BodyStrong, color = if (enabled) Shu.Paper else Shu.Ash)
                Spacer(Modifier.height(3.dp))
                Text(body, style = Type.Meta, color = Shu.Ash)
            }
            Spacer(Modifier.width(14.dp))
            Switch(
                checked = on, onCheckedChange = onChange, enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Shu.Vermilion,
                    uncheckedThumbColor = Shu.Ash,
                    uncheckedTrackColor = Shu.Booth2,
                    uncheckedBorderColor = Shu.Rule,
                ),
            )
        }
    }
}
