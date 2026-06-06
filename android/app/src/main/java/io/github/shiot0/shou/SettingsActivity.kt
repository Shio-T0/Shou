package io.github.shiot0.shou

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Where you point the app: computer IP/host, port, the REMOTE_TOKEN, and http/https. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var scanStatus: TextView

    private var nsd: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val p = getSharedPreferences("shou", MODE_PRIVATE)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        scanStatus = findViewById(R.id.scan_status)
        val token = findViewById<EditText>(R.id.token)
        val https = findViewById<SwitchCompat>(R.id.https)
        val allowCerts = findViewById<SwitchCompat>(R.id.allow_certs)

        host.setText(p.getString("host", ""))
        port.setText(p.getString("port", "4100"))
        token.setText(p.getString("token", ""))
        https.isChecked = p.getBoolean("https", false)
        allowCerts.isChecked = p.getBoolean("allowBadCerts", false)

        findViewById<Button>(R.id.scan).setOnClickListener { startDiscovery() }

        findViewById<Button>(R.id.save).setOnClickListener {
            p.edit()
                .putString("host", host.text.toString().trim())
                .putString("port", port.text.toString().trim().ifEmpty { "4100" })
                .putString("token", token.text.toString().trim())
                .putBoolean("https", https.isChecked)
                .putBoolean("allowBadCerts", allowCerts.isChecked)
                .apply()
            finish()
        }
    }

    // --------------------------------------------------------------------- //
    // mDNS / NSD auto-discovery: find a Shou server advertising _shou._tcp on
    // the LAN and fill in its host + port, so you don't have to type an IP.
    // The token still has to be entered by hand — it's a secret, never broadcast.
    // --------------------------------------------------------------------- //

    private fun startDiscovery() {
        stopDiscovery()
        val manager = (getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: run {
            scanStatus.text = getString(R.string.scan_unavailable)
            return
        }
        nsd = manager
        acquireMulticastLock()
        scanStatus.text = getString(R.string.scanning)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                // Resolve the first match to turn the service name into host:port.
                runCatching { manager.resolveService(info, makeResolveListener()) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                ui.post {
                    scanStatus.text = getString(R.string.scan_failed)
                    stopDiscovery()
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener

        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            scanStatus.text = getString(R.string.scan_failed)
            stopDiscovery()
        }

        // Give it a few seconds, then stop and report if nothing turned up.
        ui.postDelayed({
            if (discoveryListener != null) {
                if (scanStatus.text == getString(R.string.scanning)) {
                    scanStatus.text = getString(R.string.scan_none)
                }
                stopDiscovery()
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun makeResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

        override fun onServiceResolved(info: NsdServiceInfo) {
            val address = info.host?.hostAddress ?: return
            val resolvedPort = info.port
            ui.post {
                host.setText(address)
                if (resolvedPort > 0) port.setText(resolvedPort.toString())
                scanStatus.text = getString(R.string.scan_found, address, resolvedPort)
                stopDiscovery()
            }
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("shou-nsd").apply {
                setReferenceCounted(true)
                acquire()
            }
        }
    }

    private fun stopDiscovery() {
        discoveryListener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discoveryListener = null
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }

    companion object {
        private const val SERVICE_TYPE = "_shou._tcp."
        private const val SCAN_TIMEOUT_MS = 9000L
    }
}
