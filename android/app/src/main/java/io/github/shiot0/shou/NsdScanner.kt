package io.github.shiot0.shou

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/** A Shou server found on the LAN over mDNS (host + port; never the token). */
data class NsdResult(val name: String, val host: String, val port: Int)

/**
 * Reusable `_shou._tcp` discovery. Powers both the Settings "Scan" button and the
 * in-WebView Remotes page "Find on this network" action, so the phone can fill in a
 * server's address without anyone typing an IP. Resolves are serialised because the
 * platform NSD resolver only tolerates one in-flight resolve at a time.
 */
class NsdScanner(context: Context) {

    private val appCtx = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val seen = HashSet<String>()
    private var stopped = false

    /** Discover for [timeoutMs], invoking [onFound] (main thread) for each resolved
     *  Shou server, then [onDone] once with the total count. */
    fun start(timeoutMs: Long, onFound: (NsdResult) -> Unit, onDone: (Int) -> Unit) {
        val manager = appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            ui.post { onDone(0) }
            return
        }
        nsd = manager
        acquireMulticastLock()
        var found = 0

        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info) { r ->
                if (seen.add(r.name + r.host + r.port)) {
                    found++
                    onFound(r)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { ui.post { stop(); onDone(found) } }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        listener = l
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            ui.post { stop(); onDone(found) }
            return
        }
        ui.postDelayed({ if (!stopped) { stop(); onDone(found) } }, timeoutMs)
    }

    private fun enqueue(info: NsdServiceInfo, onResolved: (NsdResult) -> Unit) {
        ui.post {
            pending.addLast(info)
            pendingCallback = onResolved
            pumpResolve()
        }
    }

    private var pendingCallback: ((NsdResult) -> Unit)? = null

    private fun pumpResolve() {
        if (resolving || stopped) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        val manager = nsd ?: return
        val cb = pendingCallback
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                    ui.post { resolving = false; pumpResolve() }
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress
                    ui.post {
                        if (host != null && !stopped) {
                            cb?.invoke(NsdResult(serviceLabel(i.serviceName), host, i.port))
                        }
                        resolving = false
                        pumpResolve()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
            pumpResolve()
        }
    }

    fun stop() {
        stopped = true
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("shou-nsd").apply {
                setReferenceCounted(true); acquire()
            }
        }
    }

    /** "Shou (living-room)" -> "living-room"; otherwise the raw service name. */
    private fun serviceLabel(name: String): String {
        val m = Regex("""\(([^)]+)\)""").find(name)
        return m?.groupValues?.get(1) ?: name
    }

    companion object {
        private const val SERVICE_TYPE = "_shou._tcp."
    }
}
