package dev.tandem.app.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The server pings every 25 s: three missed pings mean the stream is dead (a Wi-Fi roam, a NAT or relay timeout). */
const val SSE_READ_TIMEOUT_MS = 75_000L

/**
 * SSE on /v1/events. Reconnects with backoff 1, 2, 5, 10, 30 s. A stream that stays silent past [readTimeoutMillis]
 * fails and reconnects, so a half-open socket is noticed. A 401 `device_revoked` means this device was removed on the
 * server: [onRevoked] is called once and the stream never reconnects.
 */
class EventStream(
    client: OkHttpClient,
    private val baseUrl: String,
    private val deviceKey: String?,
    private val onDeliveries: () -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onRevoked: () -> Unit = {},
    private val onStorage: () -> Unit = {},
    readTimeoutMillis: Long = SSE_READ_TIMEOUT_MS,
    private val backoff: LongArray = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000),
) {
    private val client = client.newBuilder().readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS).build()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tandem-sse").apply { isDaemon = true }
    }
    @Volatile private var attempt = 0
    @Volatile private var running = false
    @Volatile private var source: EventSource? = null
    @Volatile private var open = false

    /** The stream is connected now (opened, and not failed since). */
    val healthy: Boolean get() = open

    @Synchronized fun start() {
        if (running) return
        running = true
        connect()
    }

    @Synchronized fun stop() {
        running = false
        open = false
        source?.cancel()
        source = null
        scheduler.shutdownNow()
    }

    @Synchronized private fun connect() {
        if (!running) return
        val b = Request.Builder().url("$baseUrl/v1/events")
            .header("Tandem-Protocol", PROTOCOL_VERSION)
            .header("Accept", "text/event-stream")
        if (deviceKey != null) b.header("Authorization", "Bearer $deviceKey")
        source = EventSources.createFactory(client).newEventSource(b.build(), object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                attempt = 0
                open = true
                onState(true)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type == "deliveries") onDeliveries()
                else if (type == "storage") onStorage()
            }

            override fun onClosed(eventSource: EventSource) = lost()
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (response != null && isRevoked(response)) {
                    synchronized(this@EventStream) { running = false }
                    open = false
                    onState(false)
                    onRevoked()
                    return
                }
                lost()
            }
        })
    }

    private fun lost() {
        open = false
        onState(false)
        if (!running) return
        val delay = backoff[minOf(attempt, backoff.size - 1)]
        attempt++
        try {
            scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }
}

/** Whether [response] is the server's 401 `device_revoked` (C11). Reads at most a small body. */
internal fun isRevoked(response: Response): Boolean {
    if (response.code != 401) return false
    val text = runCatching { response.peekBody(4096).string() }.getOrDefault("")
    return isRevokedBody(text)
}

internal fun isRevokedBody(text: String): Boolean = Regex("\"code\"\\s*:\\s*\"device_revoked\"").containsMatchIn(text)
