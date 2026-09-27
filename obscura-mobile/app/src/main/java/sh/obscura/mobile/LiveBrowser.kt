package sh.obscura.mobile

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * Drives one Obscura page over CDP.
 *
 * Keeps an attached flattened session, runs an adaptive screenshot loop
 * (fast while the page changes, slow when idle - the same strategy Obscura's
 * own live-view tool uses) and mirrors page state into StateFlows for the UI.
 */
class LiveBrowser(
    private val client: ObscuraClient,
    private val scope: CoroutineScope
) {
    var targetId: String? = null
        private set
    var sessionId: String? = null
        private set
    /** Whether the current viewport override uses mobile emulation. */
    var viewportMobile: Boolean = true
        private set
    /** deviceScaleFactor of the current viewport override (screenshots are CSS x this). */
    var deviceScaleFactor: Int = 3
        private set

    val pageUrl = MutableStateFlow("")
    val pageTitle = MutableStateFlow("")
    val isLoading = MutableStateFlow(false)
    val frame = MutableStateFlow<ByteArray?>(null)
    val consoleLines = MutableStateFlow<List<String>>(emptyList())

    private var captureJob: Job? = null

    fun startCapturing() {
        captureJob?.cancel()
        captureJob = scope.launch(Dispatchers.Default) {
            captureLoop(this)
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
    }

    // ---------- targets ----------

    /** Prefer an existing page with real content; create one if none exists. */
    suspend fun pickOrCreateTarget(): String {
        val targets = client.listTargets()
        val pages = targets.filter { it.get("type")?.asString == "page" }
        val pick = pages.firstOrNull {
            val u = it.get("url")?.asString ?: ""
            u.isNotEmpty() && u != "about:blank" && !u.startsWith("data:")
        }
        val existing = pick?.get("id")?.asString
        if (existing != null) return existing
        val res = client.call(
            "Target.createTarget",
            JsonObject().apply { addProperty("url", "about:blank") }
        )
        return res.get("targetId")?.asString
            ?: throw ObscuraClient.CdpException("createTarget: no targetId")
    }

    suspend fun ensureAttached() {
        if (sessionId != null) return
        attachTo(pickOrCreateTarget())
    }

    /** Attach (flatten) to a specific target and enable the page domains. */
    suspend fun attachTo(tid: String) {
        targetId = null
        sessionId = null
        frame.value = null
        pageUrl.value = ""
        pageTitle.value = ""
        isLoading.value = true
        val res = try {
            client.call(
                "Target.attachToTarget",
                JsonObject().apply {
                    addProperty("targetId", tid)
                    addProperty("flatten", true)
                }
            )
        } catch (e: Exception) {
            JsonObject()
        }
        sessionId = res.get("sessionId")?.asString ?: "${tid}-session"
        targetId = tid
        runCatching { client.call("Page.enable", JsonObject(), sessionId) }
        runCatching { client.call("Runtime.enable", JsonObject(), sessionId) }
    }

    suspend fun newTab(url: String = "about:blank") {
        val res = client.call(
            "Target.createTarget",
            JsonObject().apply { addProperty("url", url) }
        )
        val tid = res.get("targetId")?.asString
            ?: throw ObscuraClient.CdpException("createTarget: no targetId")
        attachTo(tid)
    }

    suspend fun closeTab(tid: String) {
        runCatching {
            client.call(
                "Target.closeTarget",
                JsonObject().apply { addProperty("targetId", tid) }
            )
        }
        if (tid == targetId) {
            targetId = null
            sessionId = null
        }
    }

    // ---------- navigation ----------

    fun navigate(raw: String) {
        scope.launch {
            try {
                ensureAttached()
                val u = normalizeUrl(raw)
                pageUrl.value = u
                client.call(
                    "Page.navigate",
                    JsonObject().apply { addProperty("url", u) },
                    sessionId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendConsole("! " + (e.message ?: "navigate failed"))
            }
        }
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        return when {
            t.isEmpty() -> "about:blank"
            t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
    }

    private fun simpleCall(method: String) {
        scope.launch {
            try {
                ensureAttached()
                client.call(method, JsonObject(), sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    fun reload() = simpleCall("Page.reload")
    fun goBack() = simpleCall("Page.goBack")
    fun goForward() = simpleCall("Page.goForward")

    // ---------- viewport ----------

    fun setPhoneViewport() = setViewport(390, 844, 3, true)
    fun setDesktopViewport() = setViewport(1280, 800, 1, false)

    /** Choose a viewport whose aspect ratio matches the display, so the frame fills it. */
    fun setFitViewport(aspectWOverH: Float) {
        val w = 800
        val h = (w / aspectWOverH.coerceIn(0.2f, 5f)).roundToInt().coerceIn(200, 2000)
        setViewport(w, h, 2, true)
    }

    private fun setViewport(w: Int, h: Int, dpr: Int, mobile: Boolean) {
        viewportMobile = mobile
        deviceScaleFactor = dpr
        scope.launch {
            try {
                ensureAttached()
                client.call(
                    "Emulation.setDeviceMetricsOverride",
                    JsonObject().apply {
                        addProperty("width", w)
                        addProperty("height", h)
                        addProperty("deviceScaleFactor", dpr)
                        addProperty("mobile", mobile)
                    },
                    sessionId
                )
                client.call("Page.reload", JsonObject(), sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    // ---------- input (tap / swipe / type) ----------

    /** A single tap at CSS-pixel coordinates. */
    fun tap(x: Float, y: Float) {
        scope.launch {
            try {
                ensureAttached()
                if (viewportMobile) {
                    client.call(
                        "Input.dispatchTouchEvent",
                        touchEvent("touchStart", listOf(x to y)),
                        sessionId
                    )
                    client.call(
                        "Input.dispatchTouchEvent",
                        touchEvent("touchEnd", emptyList()),
                        sessionId
                    )
                } else {
                    client.call(
                        "Input.dispatchMouseEvent",
                        mouseEvent("mouseMoved", x, y),
                        sessionId
                    )
                    client.call(
                        "Input.dispatchMouseEvent",
                        mouseEvent("mousePressed", x, y, "left", 1),
                        sessionId
                    )
                    client.call(
                        "Input.dispatchMouseEvent",
                        mouseEvent("mouseReleased", x, y, "left", 1),
                        sessionId
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    /** A swipe along the given CSS-pixel path (used to scroll the page). */
    fun swipe(path: List<Pair<Float, Float>>) {
        if (path.size < 2) return
        scope.launch {
            try {
                ensureAttached()
                if (viewportMobile) {
                    client.call(
                        "Input.dispatchTouchEvent",
                        touchEvent("touchStart", listOf(path.first())),
                        sessionId
                    )
                    for (i in 1 until path.size) {
                        client.call(
                            "Input.dispatchTouchEvent",
                            touchEvent("touchMove", listOf(path[i])),
                            sessionId
                        )
                        delay(12)
                    }
                    client.call(
                        "Input.dispatchTouchEvent",
                        touchEvent("touchEnd", emptyList()),
                        sessionId
                    )
                } else {
                    // emulate wheel scrolling on the desktop viewport
                    val start = path.first()
                    val end = path.last()
                    val dy = ((start.second - end.second) / 8f).roundToInt().coerceIn(-1200, 1200)
                    client.call(
                        "Input.dispatchMouseEvent",
                        mouseEvent("mouseWheel", start.first, start.second).apply {
                            addProperty("deltaX", 0)
                            addProperty("deltaY", dy)
                        },
                        sessionId
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    /** Type text into the focused element. */
    fun typeText(text: String) {
        scope.launch {
            try {
                ensureAttached()
                client.call(
                    "Input.insertText",
                    JsonObject().apply { addProperty("text", text) },
                    sessionId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    /** Press a key (e.g. Enter) on the focused element. */
    fun pressKey(key: String) {
        scope.launch {
            try {
                ensureAttached()
                val keyCode = key.virtualKeyCode()
                client.call(
                    "Input.dispatchKeyEvent",
                    JsonObject().apply {
                        addProperty("type", "keyDown")
                        addProperty("key", key)
                        if (key == "Enter") addProperty("code", "Enter")
                        addProperty("windowsVirtualKeyCode", keyCode)
                        addProperty("nativeVirtualKeyCode", keyCode)
                        if (keyCode != 0) addProperty("keyCode", keyCode)
                    },
                    sessionId
                )
                client.call(
                    "Input.dispatchKeyEvent",
                    JsonObject().apply {
                        addProperty("type", "keyUp")
                        addProperty("key", key)
                        if (key == "Enter") addProperty("code", "Enter")
                        addProperty("windowsVirtualKeyCode", keyCode)
                        addProperty("nativeVirtualKeyCode", keyCode)
                    },
                    sessionId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    private fun touchEvent(type: String, points: List<Pair<Float, Float>>): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            add(
                "touchPoints",
                JsonArray().apply {
                    points.forEach { (x, y) ->
                        add(
                            JsonObject().apply {
                                addProperty("x", x)
                                addProperty("y", y)
                            }
                        )
                    }
                }
            )
        }

    private fun mouseEvent(
        type: String,
        x: Float,
        y: Float,
        button: String? = null,
        clickCount: Int? = null
    ): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            addProperty("x", x)
            addProperty("y", y)
            button?.let { addProperty("button", it) }
            clickCount?.let { addProperty("clickCount", it) }
        }

    private fun String.virtualKeyCode(): Int = when (this) {
        "Enter" -> 13
        "Backspace" -> 8
        "Tab" -> 9
        " " -> 32
        else -> 0
    }

    // ---------- JavaScript ----------

    fun evaluate(expression: String) {
        scope.launch {
            appendConsole("» $expression")
            try {
                ensureAttached()
                val res = client.call(
                    "Runtime.evaluate",
                    JsonObject().apply {
                        addProperty("expression", expression)
                        addProperty("returnByValue", true)
                        addProperty("awaitPromise", true)
                    },
                    sessionId
                )
                val exd = res.get("exceptionDetails")
                if (exd != null && exd.isJsonObject) {
                    val exc = exd.asJsonObject.get("exception")
                    val desc = if (exc != null && exc.isJsonObject) {
                        exc.asJsonObject.get("description")?.asString
                    } else null
                    val head = exd.asJsonObject.get("text")?.asString ?: ""
                    appendConsole("✗ " + (desc ?: head).trim())
                } else {
                    val r = res.get("result")
                    val valueText = if (r != null && r.isJsonObject) {
                        val v = r.asJsonObject.get("value")
                        when {
                            v == null || v.isJsonNull -> "undefined"
                            v.isJsonPrimitive -> v.asString
                            else -> v.toString()
                        }
                    } else ""
                    appendConsole("← $valueText")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendConsole("✗ " + (e.message ?: "eval failed"))
            }
        }
    }

    fun appendConsole(line: String) {
        consoleLines.update { (it + line).takeLast(300) }
    }

    // ---------- capture / export ----------

    suspend fun capturePng(): ByteArray {
        ensureAttached()
        val res = client.call(
            "Page.captureScreenshot",
            JsonObject().apply { addProperty("format", "png") },
            sessionId,
            30_000
        )
        val data = res.get("data")?.asString
            ?: throw ObscuraClient.CdpException("empty screenshot")
        return Base64.getDecoder().decode(data)
    }

    suspend fun printPdf(): ByteArray {
        ensureAttached()
        val res = client.call(
            "Page.printToPDF",
            JsonObject().apply { addProperty("printBackground", true) },
            sessionId,
            60_000
        )
        val data = res.get("data")?.asString
            ?: throw ObscuraClient.CdpException("empty PDF")
        return Base64.getDecoder().decode(data)
    }

    // ---------- events ----------

    fun onCdpEvent(msg: JsonObject) {
        val method = msg.get("method")?.asString ?: return
        val params = msg.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        when (method) {
            "Page.frameNavigated" -> {
                val f = params.get("frame")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
                if (f.get("parentId") == null) {
                    f.get("url")?.asString?.let { pageUrl.value = it }
                    val loader = f.get("loaderId")?.asString ?: ""
                    if (loader.isNotEmpty()) isLoading.value = true
                }
            }
            "Page.loadEventFired" -> isLoading.value = false
            "Page.titleUpdated" -> params.get("title")?.asString?.let { pageTitle.value = it }
            "Runtime.consoleAPICalled" -> {
                val args = params.get("args")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
                val text = args.joinToString(" ") { el ->
                    if (!el.isJsonObject) return@joinToString ""
                    val o = el.asJsonObject
                    val v = o.get("value")
                    when {
                        v != null && !v.isJsonNull && v.isJsonPrimitive -> v.asString
                        else -> o.get("description")?.asString
                            ?: o.get("type")?.asString
                            ?: ""
                    }
                }
                // Obscura's SSRF filter logs "Forbidden URL scheme 'blob'" for
                // pages that load dynamic scripts - noise for the user.
                if (text.contains("Forbidden URL scheme", true)) return
                val type = params.get("type")?.asString ?: "log"
                appendConsole("[$type] $text")
            }
            "Runtime.exceptionThrown" -> {
                val exd = params.get("exceptionDetails")?.takeIf { it.isJsonObject }?.asJsonObject
                val head = exd?.get("text")?.asString ?: ""
                val exc = exd?.get("exception")?.takeIf { it.isJsonObject }?.asJsonObject
                val desc = exc?.get("description")?.asString ?: ""
                appendConsole("[error] ${"$head $desc".trim()}")
            }
        }
    }

    // ---------- adaptive screenshot loop ----------

    private suspend fun captureLoop(captureScope: CoroutineScope) {
        var idle = false
        var lastHash = ""
        while (captureScope.isActive) {
            try {
                ensureAttached()
                val res = client.call(
                    "Page.captureScreenshot",
                    JsonObject().apply {
                        addProperty("format", "jpeg")
                        addProperty("quality", 70)
                    },
                    sessionId,
                    10_000
                )
                val data = res.get("data")?.asString
                var changed = false
                if (!data.isNullOrEmpty()) {
                    val bytes = Base64.getDecoder().decode(data)
                    if (bytes.size >= 100) {
                        val hash = MessageDigest.getInstance("MD5").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        if (hash != lastHash) {
                            frame.value = bytes
                            lastHash = hash
                            changed = true
                        }
                    }
                }
                idle = !changed
                delay(if (idle) 1_500L else 250L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stale session (page closed elsewhere) or a slow page:
                // drop the attach and let the next tick re-attach.
                val m = e.message ?: ""
                if (m.contains("No target", true) || m.contains("session", true)) {
                    targetId = null
                    sessionId = null
                }
                delay(1_500)
            }
        }
    }
}
