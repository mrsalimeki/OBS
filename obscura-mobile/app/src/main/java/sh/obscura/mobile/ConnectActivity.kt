package sh.obscura.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ConnectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        val hostField = findViewById<TextInputEditText>(R.id.hostField)
        val portField = findViewById<TextInputEditText>(R.id.portField)
        val tokenField = findViewById<TextInputEditText>(R.id.tokenField)
        val connectButton = findViewById<MaterialButton>(R.id.connectButton)
        val connectProgress = findViewById<ProgressBar>(R.id.connectProgress)
        val helpButton = findViewById<MaterialButton>(R.id.helpButton)

        hostField.setText(Prefs.host(this))
        portField.setText(Prefs.port(this))
        tokenField.setText(Prefs.token(this))

        helpButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        connectButton.setOnClickListener {
            val host = hostField.text.toString().trim()
            val portStr = portField.text.toString().trim()
            val token = tokenField.text.toString().trim()
            if (host.isEmpty() || portStr.isEmpty()) {
                Toast.makeText(this, R.string.connect_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.save(this, host, portStr, token)
            connectButton.isEnabled = false
            connectProgress.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    val client = ObscuraClient(host, portStr.toInt(), token)
                    val version = withContext(Dispatchers.IO) {
                        withTimeout(15_000) { client.browserVersion() }
                    }
                    Toast.makeText(this@ConnectActivity, "متصل: $version", Toast.LENGTH_SHORT).show()
                    startActivity(
                        Intent(this@ConnectActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ConnectActivity,
                        "${getString(R.string.connect_error)} (${e.message})",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    connectButton.isEnabled = true
                    connectProgress.visibility = View.GONE
                }
            }
        }
    }
}
