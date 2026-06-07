package io.github.shiot0.shou

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Per-server launcher shortcuts. Long-press the Shou icon and jump straight to
 * "Living Room" or "Bedroom" — each opens the app already pointed at that saved
 * server. Republished whenever the web remote syncs its set down to us.
 */
object Shortcuts {

    fun publish(ctx: Context) {
        val app = ctx.applicationContext
        val max = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)
            .takeIf { it > 0 } ?: 4).coerceAtMost(4)
        // Keep one slot for the static Settings shortcut; show the most useful servers.
        val remotes = ShouStore.remotes(app).take((max - 1).coerceAtLeast(1))

        val shortcuts = remotes.map { r ->
            val name = r.name.ifBlank { r.bestHost().ifBlank { "Shou" } }
            val intent = Intent(app, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_REMOTE_TOKEN, r.key)
            }
            ShortcutInfoCompat.Builder(app, "remote_" + r.id)
                .setShortLabel(name.take(10))
                .setLongLabel(name.take(25))
                .setIcon(IconCompat.createWithResource(app, R.drawable.ic_shortcut_remote))
                .setIntent(intent)
                .build()
        }
        runCatching {
            ShortcutManagerCompat.removeAllDynamicShortcuts(app)
            if (shortcuts.isNotEmpty()) ShortcutManagerCompat.addDynamicShortcuts(app, shortcuts)
        }
    }
}
