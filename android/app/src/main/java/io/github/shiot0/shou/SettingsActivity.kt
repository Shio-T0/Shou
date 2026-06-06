package io.github.shiot0.shou

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Where you point the app: computer IP/host, port, the REMOTE_TOKEN, and http/https. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val p = getSharedPreferences("shou", MODE_PRIVATE)
        val host = findViewById<EditText>(R.id.host)
        val port = findViewById<EditText>(R.id.port)
        val token = findViewById<EditText>(R.id.token)
        val https = findViewById<SwitchCompat>(R.id.https)

        host.setText(p.getString("host", ""))
        port.setText(p.getString("port", "4100"))
        token.setText(p.getString("token", ""))
        https.isChecked = p.getBoolean("https", false)

        findViewById<Button>(R.id.save).setOnClickListener {
            p.edit()
                .putString("host", host.text.toString().trim())
                .putString("port", port.text.toString().trim().ifEmpty { "4100" })
                .putString("token", token.text.toString().trim())
                .putBoolean("https", https.isChecked)
                .apply()
            finish()
        }
    }
}
