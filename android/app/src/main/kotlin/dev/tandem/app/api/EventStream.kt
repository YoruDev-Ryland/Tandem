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

/** SSE on /v1/events. Reconnects with backoff 1, 2, 5, 10, 30 s. */
class EventStream(
    client: OkHttpClient,
    private val baseUrl: String,
    private val deviceKey: String?,
    private val onDeliveries: () -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val client = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tandem-sse").apply { isDaemon = true }
    }
    private val backoff = longArrayOf(1, 2, 5, 10, 30)
    @Volatile private var attempt = 0
    @Volatile private var running = false
    @Volatile private var source: EventSource? = null

    @Synchronized fun start() {
        if (running) return
        running = true
        connect()
    }

    @Synchronized fun stop() {
        running = false
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
                onState(true)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type == "deliveries") onDeliveries()
            }

            override fun onClosed(eventSource: EventSource) = lost()
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) = lost()
        })
    }

    private fun lost() {
        onState(false)
        if (!running) return
        val delay = backoff[minOf(attempt, backoff.size - 1)]
        attempt++
        try {
            scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }
}
