package io.github.shiot0.shou

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Loads cover art for the media notification and the widget: a tiny async image fetcher
 * with an in-memory cache and helpers to center-crop + round it. Cover URLs come down
 * from the web remote with the live playback state.
 */
object ArtLoader {

    private val cache = LruCache<String, Bitmap>(12)
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun cached(url: String?): Bitmap? =
        if (url.isNullOrBlank()) null else cache.get(url)

    /** Fetch [url] (or hand back the cached bitmap immediately) and call [onReady] on the
     *  main thread. No-op for a blank URL or a fetch failure. */
    fun load(url: String?, onReady: (Bitmap) -> Unit) {
        if (url.isNullOrBlank()) return
        cache.get(url)?.let { onReady(it); return }
        io.execute {
            val bmp = fetch(url) ?: return@execute
            cache.put(url, bmp)
            main.post { onReady(bmp) }
        }
    }

    private fun fetch(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 8000
            useCaches = true
        }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    /** Center-crop [src] to [w]×[h] and round its corners by [radius] px. */
    fun roundedCrop(src: Bitmap, w: Int, h: Int, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        // Source rect that preserves aspect (center crop).
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cropW = (w / scale).toInt().coerceAtMost(src.width)
        val cropH = (h / scale).toInt().coerceAtMost(src.height)
        val sx = (src.width - cropW) / 2
        val sy = (src.height - cropH) / 2
        canvas.drawBitmap(
            src, Rect(sx, sy, sx + cropW, sy + cropH),
            RectF(0f, 0f, w.toFloat(), h.toFloat()), paint,
        )
        return out
    }
}
