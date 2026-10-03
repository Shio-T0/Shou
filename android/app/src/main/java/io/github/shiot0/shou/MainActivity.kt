package io.github.shiot0.shou

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.shiot0.shou.ui.ShouApp
import io.github.shiot0.shou.ui.ShouTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Shou remote: a native Compose app driving the PC's kiosk over the server's live
 * Socket.IO state and token-gated control endpoints. It keeps the screen awake while
 * open, turns the hardware volume rocker into the PC's volume, and hands a thrown
 * episode to [CastActivity].
 */
class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ShouStore.init(this)
        LiveLink.init(this)
        Notifications.ensureChannels(this)
        AiringWorker.schedule(this)

        // A launcher shortcut / the widget can ask for a specific saved PC.
        applyRequestedRemote(intent)

        // A remote that dims mid-episode is no remote at all.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ShouTheme {
                ShouApp(
                    vm,
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onLeave = { moveTaskToBack(true) },
                )
            }
        }

        // "Watch on phone": when the server starts a cast, open the player.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                LiveLink.state.map { it?.cast?.active == true }.distinctUntilChanged().collect { casting ->
                    if (!casting) CastActivity.handedBack = false
                    else if (!CastActivity.showing && !CastActivity.handedBack) {
                        startActivity(Intent(this@MainActivity, CastActivity::class.java))
                    }
                }
            }
        }

        maybeRequestNotifications()
    }

    override fun onStart() {
        super.onStart()
        vm.refreshRemotes()
        LiveLink.foreground()
    }

    override fun onStop() {
        LiveLink.background()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyRequestedRemote(intent)
    }

    // --- Hardware volume rocker -> the PC's player ------------------------- //
    // A remote should drive the TV's volume, not the phone's ringer (Settings, on by
    // default): swallow the keys and nudge mpv through /volume instead.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isVol = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVol && ShouStore.volumeKeys(this) && LiveLink.link.value == Link.LIVE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                vm.volume(if (code == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** A shortcut/widget may carry a target server token; make it the active one. */
    private fun applyRequestedRemote(intent: Intent?) {
        val token = intent?.getStringExtra(EXTRA_REMOTE_TOKEN)?.trim().orEmpty()
        if (token.isEmpty()) return
        val r = ShouStore.remoteByToken(this, token) ?: return
        vm.switchTo(r)
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    companion object {
        const val EXTRA_REMOTE_TOKEN = "io.github.shiot0.shou.REMOTE_TOKEN"
    }
}
