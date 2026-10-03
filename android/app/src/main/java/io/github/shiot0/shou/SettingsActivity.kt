package io.github.shiot0.shou

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
