package sh.obscura.mobile

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import kotlin.math.abs
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private val scope: CoroutineScope = lifecycleScope
    private lateinit var client: ObscuraClient
    private lateinit var browser: LiveBrowser

    private lateinit var frameView: ImageView
    private lateinit var urlField: EditText
    private lateinit var navProgress: ProgressBar
    private lateinit var pageTitleView: TextView
    private lateinit var consoleView: TextView
    private lateinit var typeButton: ImageButton
    private lateinit var viewportButton: MaterialButton
    private var urlFocused = false
    private var reconnectDialogVisible = false

    // Last decoded frame size in device pixels (screenshot = CSS x deviceScaleFactor).
    private var frameW = 0
    private var frameH = 0

    // Touch tracking on the live frame.
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private val touchPath = ArrayList<Pair<Float, Float>>()

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
        viewportButton = findViewById(R.id.viewportButton)
        val screenshotButton = findViewById<ImageButton>(R.id.screenshotButton)
        val pdfButton = findViewById<ImageButton>(R.id.pdfButton)
        val tabsButton = findViewById<MaterialButton>(R.id.tabsButton)
        val infoButton = findViewById<ImageButton>(R.id.infoButton)
        val evalButton = findViewById<MaterialButton>(R.id.evalButton)
        val statusText = findViewById<TextView>(R.id.statusText)
        typeButton = findViewById(R.id.typeButton)

        statusText.text = getString(R.string.server_label, host, port)
        urlField.setOnFocusChangeListener { _, hasFocus -> urlFocused = hasFocus }
        frameView.setOnTouchListener { _, ev -> onFrameTouch(ev) }

        // Fill the screen by default: match the viewport to the display aspect ratio.
        frameView.post {
            if (frameView.width > 0 && frameView.height > 0) applyFitViewport()
        }

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
        typeButton.setOnClickListener { showTypeDialog() }

        scope.launch {
            browser.frame.collect { bytes ->
                if (bytes == null) {
                    frameView.setImageBitmap(null)
                    return@collect
                }
                val bmp = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null) {
                    frameW = bmp.width
                    frameH = bmp.height
                    frameView.setImageBitmap(bmp)
                }
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
        menu.menu.add(0, 2, 2, getString(R.string.viewport_fit))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> {
                    browser.setPhoneViewport()
                    viewportButton.setText(R.string.viewport_phone)
                }
                1 -> {
                    browser.setDesktopViewport()
                    viewportButton.setText(R.string.viewport_desktop)
                }
                2 -> applyFitViewport()
            }
            true
        }
        menu.show()
    }

    private fun applyFitViewport() {
        if (frameView.width > 0 && frameView.height > 0) {
            browser.setFitViewport(frameView.width.toFloat() / frameView.height.toFloat())
            viewportButton.setText(R.string.viewport_fit)
        }
    }

    // ---------- frame touch -> page input ----------

    /** Tap = click on the page, drag = scroll. Coordinates are mapped to CSS pixels. */
    private fun onFrameTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = SystemClock.elapsedRealtime()
                moved = false
                touchPath.clear()
                touchPath.add(ev.x to ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)) {
                    moved = true
                }
                if (moved) {
                    val last = touchPath.last()
                    if (abs(ev.x - last.first) > 2f || abs(ev.y - last.second) > 2f) {
                        touchPath.add(ev.x to ev.y)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moved) {
                    touchPath.add(ev.x to ev.y)
                    val path = touchPath.mapNotNull { toCss(it.first, it.second) }
                    if (path.size >= 2) browser.swipe(path)
                } else if (SystemClock.elapsedRealtime() - downTime < 600L) {
                    val p = toCss(ev.x, ev.y)
                    if (p != null) browser.tap(p.first, p.second)
                }
            }
        }
        return true
    }

    /** Map a point in frameView pixels to page CSS pixels (null when no frame). */
    private fun toCss(viewX: Float, viewY: Float): Pair<Float, Float>? {
        val bw = frameW
        val bh = frameH
        val vw = frameView.width
        val vh = frameView.height
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return null
        val scale = min(vw.toFloat() / bw, vh.toFloat() / bh)
        val offX = (vw - bw * scale) / 2f
        val offY = (vh - bh * scale) / 2f
        val dpr = browser.deviceScaleFactor.coerceAtLeast(1).toFloat()
        val cx = (viewX - offX) / scale / dpr
        val cy = (viewY - offY) / scale / dpr
        return cx to cy
    }

    // ---------- text input ----------

    private fun showTypeDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.type_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(64, 40, 64, 8)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.type_dialog_title)
            .setMessage(R.string.type_dialog_note)
            .setView(input)
            .setPositiveButton(R.string.type_enter) { _, _ ->
                val t = input.text.toString()
                if (t.isNotEmpty()) {
                    browser.typeText(t)
                    browser.pressKey("Enter")
                }
            }
            .setNeutralButton(R.string.type_only) { _, _ ->
                val t = input.text.toString()
                if (t.isNotEmpty()) browser.typeText(t)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
