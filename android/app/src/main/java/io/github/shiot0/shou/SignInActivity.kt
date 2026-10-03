package io.github.shiot0.shou

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shiot0.shou.ui.Glyph
import io.github.shiot0.shou.ui.GhostButton
import io.github.shiot0.shou.ui.PrimaryButton
import io.github.shiot0.shou.ui.RoundButton
import io.github.shiot0.shou.ui.Seal
import io.github.shiot0.shou.ui.Shu
import io.github.shiot0.shou.ui.ShouTheme
import io.github.shiot0.shou.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sign the PC in to AniList from the phone. AniList's own login page opens here; after
 * you tap Authorize it redirects to its PIN page with `#access_token=…` in the URL, which
 * we catch and hand to the PC (POST /auth/anilist). The PC checks it, saves it and
 * switches to that account's lists — no terminal, no daemon restart.
 */
class SignInActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ShouStore.init(this)
        LiveLink.init(this)
        // "Use a different account": forget AniList's session so the login form shows.
        if (intent.getBooleanExtra(EXTRA_FRESH, false)) CookieManager.getInstance().removeAllCookies(null)
        setContent { ShouTheme { SignInScreen(onDone = ::finish) } }
    }

    companion object {
        private const val EXTRA_FRESH = "fresh"

        fun start(ctx: Context, differentAccount: Boolean = false) {
            ctx.startActivity(Intent(ctx, SignInActivity::class.java).putExtra(EXTRA_FRESH, differentAccount))
        }
    }
}

private sealed interface Step {
    data object Browse : Step
    data object Saving : Step
    data class Done(val name: String) : Step
    data class Failed(val reason: String) : Step
}

@Composable
private fun SignInScreen(onDone: () -> Unit) {
    val state by LiveLink.state.collectAsStateWithLifecycle()
    val account = state?.account
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var step by remember { mutableStateOf<Step>(Step.Browse) }
    var newClient by remember { mutableStateOf(false) }

    fun submit(token: String) {
        step = Step.Saving
        scope.launch {
            val (code, reply) = withContext(Dispatchers.IO) {
                ServerClient.postForm(ctx, "auth/anilist", mapOf("token" to token))
            }
            step = when {
                code in 200..299 && reply?.optBoolean("ok") == true -> Step.Done(reply.optString("name"))
                code == -1 -> Step.Failed("Couldn't reach the PC. Check that Shou is running and try again.")
                else -> Step.Failed(reply?.optString("reason")?.ifBlank { null } ?: "The PC couldn't save the sign-in.")
            }
        }
    }

    LaunchedEffect(step) {
        if (step is Step.Done) { delay(1400); onDone() }
    }

    Column(Modifier.fillMaxSize().background(Shu.Ink).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Glyph.Close, "Cancel", onDone, size = 44.dp, color = Color.Transparent, border = false)
            Spacer(Modifier.width(6.dp))
            Text("Sign in to AniList", style = Type.TitleSmall, color = Shu.Paper)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                account == null -> Notice(
                    if (state == null) "Connect to your Shou PC first, then sign in from here."
                    else "Update Shou on the PC (git pull, then restart it) to sign in from the phone.",
                )
                step is Step.Saving -> Working("Signing the PC in…")
                step is Step.Done -> Finished((step as Step.Done).name)
                step is Step.Failed -> Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text((step as Step.Failed).reason, style = Type.Body, color = Shu.Paper, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("Try again", { step = Step.Browse }, Modifier.fillMaxWidth())
                }
                account.authUrl.isBlank() || newClient -> ClientSetup(account.redirect) { newClient = false }
                else -> Column(Modifier.fillMaxSize()) {
                    AniListPage(account.authUrl, account.redirect, onToken = ::submit, modifier = Modifier.weight(1f))
                    Text(
                        "Not working? Set up a new AniList app ID",
                        style = Type.Small, color = Shu.Ash, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { newClient = true }.padding(12.dp),
                    )
                }
            }
        }
    }
}

/** AniList in a WebView, watching every navigation for the PIN-page redirect. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AniListPage(authUrl: String, redirect: String, onToken: (String) -> Unit, modifier: Modifier) {
    var loading by remember { mutableStateOf(true) }
    Box(modifier.fillMaxWidth()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AColor.parseColor("#0A090C"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    var handed = false
                    fun check(url: String?) {
                        if (handed || url == null || !url.startsWith(redirect)) return
                        val fragment = Uri.parse(url).fragment ?: return
                        val token = Uri.parse("shou://t?$fragment").getQueryParameter("access_token") ?: return
                        handed = true
                        stopLoading()
                        onToken(token)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            check(request.url.toString()); return false
                        }
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { check(url) }
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { check(url) }
                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            check(url)
                            // Some redirects only expose the fragment to the page itself.
                            if (!handed && url?.startsWith(redirect) == true) {
                                view.evaluateJavascript("location.href") { href -> check(href?.trim('"')) }
                            }
                        }
                    }
                    loadUrl(authUrl)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) Working("Opening AniList…")
    }
}

/** First-time only: register Shou with AniList and paste its Client ID. */
@Composable
private fun ClientSetup(redirect: String, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text("One-time setup", style = Type.Heading, color = Shu.Paper)
        Spacer(Modifier.height(8.dp))
        Text(
            "AniList needs to know about your Shou before it can sign in. This takes a minute, once:",
            style = Type.Body, color = Shu.Ash,
        )
        Spacer(Modifier.height(16.dp))
        for ((i, line) in listOf(
            "Open AniList's developer settings and choose Create New Client.",
            "Name it Shou, and set the Redirect URL to\n$redirect",
            "Save, then copy the Client ID (a number) and paste it below.",
        ).withIndex()) {
            Row(Modifier.padding(bottom = 12.dp)) {
                Text("${i + 1}", style = Type.Label, color = Shu.Vermilion, modifier = Modifier.width(22.dp))
                // Selectable, so the redirect URL can be copied into AniList's form.
                SelectionContainer { Text(line, style = Type.Body, color = Shu.Paper) }
            }
        }
        GhostButton(
            "Open developer settings", { uri.openUri("https://anilist.co/settings/developer") },
            Modifier.fillMaxWidth(), icon = Glyph.ChevronRight, tint = Shu.Vermilion,
        )
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = id,
            onValueChange = { id = it.filter(Char::isDigit).take(10); error = null },
            label = { Text("Client ID", style = Type.Meta) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it, style = Type.Small) } },
            textStyle = Type.Body.copy(color = Shu.Paper),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Shu.Vermilion, unfocusedBorderColor = Shu.Rule,
                focusedLabelColor = Shu.Vermilion, unfocusedLabelColor = Shu.Ash, cursorColor = Shu.Vermilion,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            if (saving) "Saving…" else "Continue to sign in",
            onClick = {
                saving = true
                scope.launch {
                    val (code, reply) = withContext(Dispatchers.IO) {
                        ServerClient.postForm(ctx, "auth/client", mapOf("id" to id))
                    }
                    saving = false
                    if (code in 200..299) onSaved()
                    else error = reply?.optString("reason")?.ifBlank { null } ?: "Couldn't reach the PC."
                }
            },
            enabled = id.isNotBlank() && !saving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Working(label: String) {
    Column(
        Modifier.fillMaxSize().background(Shu.Ink),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), color = Shu.Vermilion, strokeWidth = 2.5.dp)
        Spacer(Modifier.height(14.dp))
        Text(label, style = Type.Meta, color = Shu.Ash)
    }
}

@Composable
private fun Finished(name: String) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).background(Shu.Jade.copy(alpha = 0.14f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Check, null, Modifier.size(30.dp), tint = Shu.Jade) }
        Spacer(Modifier.height(18.dp))
        Text("Signed in as $name", style = Type.Title, color = Shu.Paper, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Shou now shows $name's lists and keeps them up to date.", style = Type.Body, color = Shu.Ash, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Notice(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Seal(size = 56.dp, lit = false)
        Spacer(Modifier.height(18.dp))
        Text(text, style = Type.Body, color = Shu.Paper, textAlign = TextAlign.Center)
    }
}
