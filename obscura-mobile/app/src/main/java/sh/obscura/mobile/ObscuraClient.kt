package sh.obscura.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin client for an Obscura CDP server (`obscura serve`).
 *
 * Endpoints (standard Chrome DevTools Protocol):
 *  - HTTP discovery: GET /json/version, GET /json/list
 *  - WebSocket:      /devtools/browser
 *
 * When the server was started with OBSCURA_CDP_TOKEN (required for any
 * non-loopback bind), every request carries `Authorization: Bearer <token>`.
 */
class ObscuraClient(
    val host: String,
    val port: Int,
    private val token: String?
) {
    val baseUrl: String get() = "http://$host:$port"
    val wsUrl: String get() = "ws://$host:$port/devtools/browser"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    var wsConnected: Boolean = false
        private set

    /** Invoked (on a background thread) for interesting page events. */
    var onEvent: ((JsonObject) -> Unit)? = null

    /** Invoked (on a background thread) when the socket dies. */
    var onClosed: (() -> Unit)? = null

    private fun withAuth(b: Request.Builder): Request.Builder {
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        return b
    }

    /** GET /json/version -> the "Browser" identity string. */
    suspend fun browserVersion(): String {
        val req = withAuth(Request.Builder().url("$baseUrl/json/version")).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw CdpException("HTTP ${resp.code} from /json/version")
            val body = resp.body?.string() ?: throw CdpException("empty response")
            val obj = JsonParser.parseString(body).asJsonObject
            return obj.get("Browser")?.asString ?: "Obscura"
        }
    }

    /** GET /json/list -> every page target across all client connections. */
    suspend fun listTargets(): List<JsonObject> {
        val req = withAuth(Request.Builder().url("$baseUrl/json/list")).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw CdpException("HTTP ${resp.code} from /json/list")
            val body = resp.body?.string() ?: throw CdpException("empty response")
            val arr = JsonParser.parseString(body).asJsonArray
            return arr.map { it.asJsonObject }
        }
    }

    /** Open the browser-level WebSocket. No-op while a socket exists. */
    fun connectWebSocket() {
        if (ws != null) return
        val req = withAuth(Request.Builder().url(wsUrl)).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try {
                    JsonParser.parseString(text).asJsonObject
                } catch (e: Exception) {
                    return
                }
                val id = msg.get("id")?.asNumber
                if (id != null) {
                    val def = pending.remove(id.toInt())
                    if (def != null) {
                        val err = msg.get("error")
                        if (err != null && err.isJsonObject) {
                            val message = err.asJsonObject.get("message")?.asString ?: "CDP error"
                            def.completeExceptionally(CdpException(message))
                        } else {
                            val result = msg.get("result")
                            def.complete(
                                if (result != null && result.isJsonObject) result.asJsonObject
                                else JsonObject()
                            )
                        }
                    }
                    return
                }
                val method = msg.get("method")?.asString ?: return
                when (method) {
                    "Page.frameNavigated",
                    "Page.loadEventFired",
                    "Page.titleUpdated",
                    "Runtime.consoleAPICalled",
                    "Runtime.exceptionThrown" -> onEvent?.invoke(msg)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsConnected = false
                failAllPending(t)
                onClosed?.invoke()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsConnected = false
                failAllPending(CdpException("socket closed: $code $reason"))
                onClosed?.invoke()
            }
        })
    }

    /** Drop the socket without waiting; suppresses further callbacks. */
    fun forceReset() {
        ws?.cancel()
        ws = null
        wsConnected = false
        failAllPending(CdpException("reset by app"))
    }

    /** Graceful close (activity shutdown). */
    fun close() {
        ws?.close(1000, "bye")
        ws = null
        wsConnected = false
        failAllPending(CdpException("closed by app"))
    }

    private fun failAllPending(t: Throwable) {
        pending.values.forEach { it.completeExceptionally(t) }
        pending.clear()
    }

    private suspend fun ensureOpen() {
        check(ws != null) { "WebSocket is not connected" }
        withTimeout(15_000) {
            while (!wsConnected) delay(100)
        }
    }

    /**
     * Send a CDP command and wait for its reply.
     * [sessionId] targets a specific flattened page session.
     */
    suspend fun call(
        method: String,
        params: JsonObject? = null,
        sessionId: String? = null,
        timeoutMs: Long = 30_000
    ): JsonObject {
        ensureOpen()
        val socket = ws!!
        val id = nextId.getAndIncrement()
        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            params?.let { add("params", it) }
            sessionId?.let { addProperty("sessionId", it) }
        }
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val ok = socket.send(msg.toString())
        if (!ok) {
            pending.remove(id)
            throw CdpException("could not send: $method")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Throwable) {
            pending.remove(id)
            throw e
        }
    }

    class CdpException(message: String) : RuntimeException(message)
}
