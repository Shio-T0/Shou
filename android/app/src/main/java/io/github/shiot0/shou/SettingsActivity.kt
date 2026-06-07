package io.github.shiot0.shou

import android.os.Bundle
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
    private var scanner: NsdScanner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ShouStore.init(this)

        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        scanStatus = findViewById(R.id.scan_status)
        val token = findViewById<EditText>(R.id.token)
        val https = findViewById<SwitchCompat>(R.id.https)
        val allowCerts = findViewById<SwitchCompat>(R.id.allow_certs)
        val volumeKeys = findViewById<SwitchCompat>(R.id.volume_keys)

        host.setText(ShouStore.host(this))
        port.setText(ShouStore.port(this))
        token.setText(ShouStore.token(this))
        https.isChecked = ShouStore.https(this)
        allowCerts.isChecked = ShouStore.allowBadCerts(this)
        volumeKeys.isChecked =
            getSharedPreferences("shou", MODE_PRIVATE).getBoolean("volumeKeys", true)

        findViewById<Button>(R.id.scan).setOnClickListener { startDiscovery() }

        findViewById<Button>(R.id.save).setOnClickListener {
            ShouStore.saveSettings(
                this,
                host.text.toString(),
                port.text.toString(),
                token.text.toString(),
                https.isChecked,
                allowCerts.isChecked,
            )
            getSharedPreferences("shou", MODE_PRIVATE).edit()
                .putBoolean("volumeKeys", volumeKeys.isChecked)
                .apply()
            // A single typed-in server is also the active one; mirror it so background
            // features (media controls, widget, tile) have somewhere to point.
            ShouStore.setActive(
                this, token.text.toString().trim(),
                host.text.toString().trim(),
                port.text.toString().trim().ifEmpty { "4100" },
                host.text.toString().trim().ifEmpty { "Shou" },
            )
            finish()
        }
    }

    // mDNS / NSD auto-discovery: find a Shou server advertising _shou._tcp on the LAN and
    // fill in its host + port, so you don't have to type an IP. The token is never broadcast.
    private fun startDiscovery() {
        scanner?.stop()
        scanStatus.text = getString(R.string.scanning)
        var got = false
        val s = NsdScanner(this)
        scanner = s
        s.start(
            timeoutMs = 9000,
            onFound = { r ->
                if (!got) {
                    got = true
                    host.setText(r.host)
                    if (r.port > 0) port.setText(r.port.toString())
                    scanStatus.text = getString(R.string.scan_found, r.host, r.port)
                    s.stop()
                }
            },
            onDone = { if (!got) scanStatus.text = getString(R.string.scan_none) },
        )
    }

    override fun onDestroy() {
        scanner?.stop()
        super.onDestroy()
    }
}
