package sh.obscura.mobile

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val scope: CoroutineScope = lifecycleScope
    private lateinit var client: ObscuraClient
    private lateinit var browser: LiveBrowser

    private lateinit var frameView: ImageView
    private lateinit var urlField: EditText
    private lateinit var navProgress: ProgressBar
    private lateinit var pageTitleView: TextView
    private lateinit var consoleView: TextView
    private var urlFocused = false
    private var reconnectDialogVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val host = Prefs.host(this)
        val port = Prefs.port(this).toIntOrNull() ?: 9222
        val token = Prefs.token(this)
        client = ObscuraClient(host, port, token)
        browser = LiveBrowser(client, scope)

        frameView = findViewById(R.id.frameView)
        urlField = findViewById(R.id.urlField)
        navProgress = findViewById(R.id.navProgress)
        pageTitleView = findViewById(R.id.pageTitle)
        consoleView = findViewById(R.id.consoleView)
        val evalField = findViewById<EditText>(R.id.evalField)
        val goButton = findViewById<MaterialButton>(R.id.goButton)
        val backButton = findViewById<ImageButton>(R.id.backButton)
        val forwardButton = findViewById<ImageButton>(R.id.forwardButton)
        val reloadButton = findViewById<ImageButton>(R.id.reloadButton)
        val viewportButton = findViewById<MaterialButton>(R.id.viewportButton)
        val screenshotButton = findViewById<ImageButton>(R.id.screenshotButton)
        val pdfButton = findViewById<ImageButton>(R.id.pdfButton)
        val tabsButton = findViewById<MaterialButton>(R.id.tabsButton)
        val infoButton = findViewById<ImageButton>(R.id.infoButton)
        val evalButton = findViewById<MaterialButton>(R.id.evalButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        statusText.text = getString(R.string.server_label, host, port)
        urlField.setOnFocusChangeListener { _, hasFocus -> urlFocused = hasFocus }

        client.onEvent = { msg -> browser.onCdpEvent(msg) }
        client.onClosed = {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showDisconnectDialog()
            }
        }
        client.connectWebSocket()
        browser.startCapturing()

        goButton.setOnClickListener {
            browser.navigate(urlField.text.toString())
            urlField.clearFocus()
        }
        backButton.setOnClickListener { browser.goBack() }
        forwardButton.setOnClickListener { browser.goForward() }
        reloadButton.setOnClickListener { browser.reload() }
        screenshotButton.setOnClickListener { captureScreenshot() }
        pdfButton.setOnClickListener { exportPdf() }
        tabsButton.setOnClickListener { showTabsMenu(it) }
        infoButton.setOnClickListener { showHelp() }
        viewportButton.setOnClickListener { showViewportMenu(it) }
        evalButton.setOnClickListener {
            val expr = evalField.text.toString().trim()
            if (expr.isNotEmpty()) {
                browser.evaluate(expr)
                evalField.setText("")
            }
        }

        scope.launch {
            browser.frame.collect { bytes ->
                if (bytes == null) {
                    frameView.setImageBitmap(null)
                    return@collect
                }
                val bmp = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null) frameView.setImageBitmap(bmp)
            }
        }
        scope.launch {
            browser.pageUrl.collect { u ->
                if (!urlFocused && urlField.text.toString() != u) urlField.setText(u)
            }
        }
        scope.launch {
            browser.pageTitle.collect { pageTitleView.text = it }
        }
        scope.launch {
            browser.isLoading.collect {
                navProgress.visibility = if (it) View.VISIBLE else View.GONE
            }
        }
        scope.launch {
            browser.consoleLines.collect { lines ->
                val text = lines.joinToString("\n")
                if (consoleView.text.toString() != text) {
                    consoleView.text = text
                    consoleView.post {
                        // scroll to the bottom of the content
                        val contentHeight = consoleView.layout?.height ?: 0
                        consoleView.scrollTo(0, contentHeight)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onDestroy() {
        browser.stop()
        client.close()
        super.onDestroy()
    }

    // ---------- menus ----------

    private fun showViewportMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 0, 0, getString(R.string.viewport_phone))
        menu.menu.add(0, 1, 1, getString(R.string.viewport_desktop))
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == 0) browser.setPhoneViewport()
            else browser.setDesktopViewport()
            true
        }
        menu.show()
    }

    private fun showTabsMenu(anchor: View) {
        scope.launch {
            val targets = withContext(Dispatchers.IO) {
                runCatching { client.listTargets() }
                    .getOrDefault(emptyList())
                    .filter { it.get("type")?.asString == "page" }
            }
            val menu = PopupMenu(this@MainActivity, anchor)
            menu.menu.add(0, 0, 0, getString(R.string.new_tab))
            targets.forEachIndexed { i, t ->
                val id = t.get("id")?.asString ?: ""
                val title = (t.get("title")?.asString ?: "").ifEmpty {
                    t.get("url")?.asString ?: id.take(16)
                }
                val mark = if (id == browser.targetId) "● " else "○ "
                menu.menu.add(1, i + 1, i + 1, "$mark${title.take(42)}")
            }
            if (targets.isNotEmpty()) {
                menu.menu.add(2, 900, 900, getString(R.string.refresh_list))
            }
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { browser.newTab() } }
                        }
                        true
                    }
                    900 -> {
                        showTabsMenu(anchor)
                        true
                    }
                    else -> {
                        val idx = item.itemId - 1
                        if (idx in targets.indices) {
                            val id = targets[idx].get("id")?.asString ?: ""
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { browser.attachTo(id) } }
                            }
                        }
                        true
                    }
                }
            }
            menu.show()
        }
    }

    // ---------- capture / export ----------

    private fun captureScreenshot() {
        Toast.makeText(this, R.string.capturing, Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { browser.capturePng() }
                val file = saveFile(bytes, "obscura-screenshot-${System.currentTimeMillis()}.png")
                offerShare(file, "image/png")
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportPdf() {
        Toast.makeText(this, R.string.capturing, Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { browser.printPdf() }
                val file = saveFile(bytes, "obscura-page-${System.currentTimeMillis()}.pdf")
                offerShare(file, "application/pdf")
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveFile(bytes: ByteArray, name: String): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file
    }

    private fun offerShare(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Toast.makeText(this, getString(R.string.saved_to, file.absolutePath), Toast.LENGTH_LONG).show()
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    // ---------- dialogs ----------

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showDisconnectDialog() {
        if (reconnectDialogVisible) return
        reconnectDialogVisible = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disconnected_title)
            .setMessage(R.string.disconnected_body)
            .setPositiveButton(R.string.retry) { _, _ ->
                reconnectDialogVisible = false
                reconnect()
            }
            .setNegativeButton(R.string.go_connect) { _, _ ->
                reconnectDialogVisible = false
                startActivity(Intent(this, ConnectActivity::class.java))
            }
            .setOnDismissListener { reconnectDialogVisible = false }
            .show()
    }

    private fun reconnect() {
        client.forceReset()
        client.connectWebSocket()
    }
}
