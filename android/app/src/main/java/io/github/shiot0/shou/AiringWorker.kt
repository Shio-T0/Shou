package io.github.shiot0.shou

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Periodically asks the active Shou server whether any show on your Watching list has
 * a new episode out (beyond your progress), and notifies you when one does. This is the
 * "new episode aired" push — done by polling your own PC on the LAN, so it needs no FCM
 * and no account, in keeping with how the rest of Shou works.
 *
 * It only fires when the phone can actually reach the server; the first run after install
 * primes the "already seen" set silently so you don't get buried in a backlog.
 */
class AiringWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        ShouStore.init(ctx)
        val raw = ServerClient.airing(ctx) ?: return Result.success()  // offline / not set up
        val shows = try {
            JSONObject(raw).optJSONArray("shows") ?: return Result.success()
        } catch (e: Exception) {
            return Result.success()
        }

        val prefs = ctx.getSharedPreferences("shou_airing", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
        val primed = prefs.getBoolean("primed", false)

        for (i in 0 until shows.length()) {
            val s = shows.optJSONObject(i) ?: continue
            val id = s.optInt("id")
            val progress = s.optInt("progress")
            val available = s.optInt("available")
            val title = s.optString("title")
            if (available <= progress) continue          // nothing new past where you are
            val marker = "$id:$available"
            if (!seen.add(marker)) continue               // already told you about this one
            if (primed) {
                Notifications.event(
                    ctx, "airing", title,
                    "Episode $available is out — ${available - progress} new to watch",
                )
            }
        }

        prefs.edit()
            .putStringSet("seen", seen.takeLast(200).toSet())
            .putBoolean("primed", true)
            .apply()
        return Result.success()
    }

    companion object {
        private const val WORK = "shou-airing-check"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<AiringWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun Set<String>.takeLast(n: Int): List<String> =
            this.toList().let { if (it.size <= n) it else it.subList(it.size - n, it.size) }
    }
}
