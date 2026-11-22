package dev.tandem.app.api

import dev.tandem.app.model.ItemKind
import dev.tandem.app.model.ManifestEntry
import dev.tandem.app.model.SaveUploadRequest
import dev.tandem.app.model.SaveUploadStatus
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun base() = server.url("/").toString().trimEnd('/')

    @Test fun sends_protocol_header_and_bearer() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"items":[]}""").build())
        val items = ApiClient(base(), "key123").deliveries()
        assertTrue(items.isEmpty())
        val r = server.takeRequest()
        assertEquals("1", r.headers["Tandem-Protocol"])
        assertEquals("Bearer key123", r.headers["Authorization"])
        assertEquals("/v1/deliveries", r.url.encodedPath)
    }

    @Test fun parses_error_body_into_ApiException() = runBlocking {
        server.enqueue(MockResponse.Builder().code(409).body("""{"code":"conflict","message":"nope"}""").build())
        try {
            ApiClient(base(), "k").login("a@b.c", "pw", "dev")
            fail("expected")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
            assertEquals("conflict", e.code)
            assertEquals("nope", e.message)
        }
        Unit
    }

    @Test fun uploadSave_serializes_enum_as_string() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"status":"Accepted","itemId":"i","versionId":"v","headVersionId":"h","extra":1}""").build())
        val resp = ApiClient(base(), "k").uploadSave(
            SaveUploadRequest("e", ItemKind.Save, "g", "", "", null, listOf(ManifestEntry("a", "h", 1)), null))
        assertEquals(SaveUploadStatus.Accepted, resp.status)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"kind\":\"Save\""))
        assertTrue(body, body.contains("\"manifest\""))
    }

    @Test fun uploadSave_sends_empty_slot_and_core() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"status":"Accepted","itemId":"i","versionId":"v","headVersionId":"h"}""").build())
        ApiClient(base(), "k").uploadSave(
            SaveUploadRequest("e", ItemKind.Save, "g", "", "", null, listOf(ManifestEntry("a", "h", 1)), null))
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"slot\":\"\""))
        assertTrue(body, body.contains("\"core\":\"\""))
    }

    @Test fun putBlob_streams_body_with_octet_stream() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        val data = "hello".toByteArray()
        ApiClient(base(), "k").putBlob("abc", data.size.toLong()) { ByteArrayInputStream(data) }
        val r = server.takeRequest()
        assertEquals("PUT", r.method)
        assertEquals("/v1/blobs/abc", r.url.encodedPath)
        assertEquals("application/octet-stream", r.headers["Content-Type"])
        assertEquals("5", r.headers["Content-Length"])
        assertEquals("hello", r.body!!.utf8())
    }

    @Test fun event_stream_calls_onDeliveries_for_deliveries_event() {
        server.enqueue(MockResponse.Builder().code(200)
            .setHeader("Content-Type", "text/event-stream")
            .body("event: ping\ndata: {}\n\nevent: deliveries\ndata: {}\n\n").build())
        val latch = CountDownLatch(1)
        val stream = EventStream(OkHttpClient(), base(), "k", { latch.countDown() }, { })
        stream.start()
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("Bearer k", server.takeRequest().headers["Authorization"])
        } finally { stream.stop() }
    }

    private val revokedBody = """{"code":"device_revoked","message":"This device has been revoked."}"""

    @Test fun a_revoked_key_is_reported_before_the_call_throws() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body(revokedBody).build())
        server.enqueue(MockResponse.Builder().code(401).body("""{"code":"unauthorized"}""").build())
        var revoked = 0
        val c = ApiClient(base(), "k", onRevoked = { revoked++ })
        try { c.deliveries(); fail("expected 401") } catch (e: ApiException) { assertTrue(e.isRevoked) }
        assertEquals(1, revoked)
        try { c.deliveries(); fail("expected 401") } catch (e: ApiException) { assertEquals(false, e.isRevoked) }
        assertEquals("another 401 is not a revoke", 1, revoked)
    }

    @Test fun event_stream_stops_reconnecting_once_the_device_is_revoked() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(401).body(revokedBody).build()) }
        val revoked = CountDownLatch(1)
        val states = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val stream = EventStream(OkHttpClient(), base(), "k", {}, { states += it }, onRevoked = { revoked.countDown() },
            backoff = longArrayOf(20))
        stream.start()
        try {
            assertTrue(revoked.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)   // ample for several 20 ms reconnects, had it kept trying
            assertEquals(1, server.requestCount)
            assertEquals(listOf(false), states.toList())
            assertEquals(false, stream.healthy)
        } finally { stream.stop() }
    }

    @Test fun event_stream_reconnects_when_the_server_goes_silent() {
        // The first stream opens and then says nothing; the read timeout ends it and the second one delivers.
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream")
            .body("event: ping\ndata: {}\n\n").bodyDelay(3, TimeUnit.SECONDS).build())
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream")
            .body("event: deliveries\ndata: {}\n\n").build())
        val latch = CountDownLatch(1)
        val stream = EventStream(OkHttpClient(), base(), "k", { latch.countDown() }, {}, readTimeoutMillis = 300,
            backoff = longArrayOf(20))
        stream.start()
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertTrue(server.requestCount >= 2)
        } finally { stream.stop() }
    }

    @Test fun the_default_read_timeout_outlasts_two_missed_pings() {
        assertTrue(SSE_READ_TIMEOUT_MS in 60_000L..90_000L)
    }

    @Test fun timed_status_put_fails_instead_of_landing_late() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).headersDelay(3, TimeUnit.SECONDS).build())
        val req = dev.tandem.app.model.DeviceStatusRequest("android", "0", true, emptyList(), emptyMap(), listOf("eden"))
        val started = System.nanoTime()
        try {
            ApiClient(base(), "k").putStatus(req, callTimeoutMillis = 300)
            fail("expected the call timeout")
        } catch (e: java.io.IOException) {
            // OkHttp's call timeout cancels the call: it fails rather than completing after the server answers
        }
        assertTrue("cancelled by the call timeout, not the 3 s answer", System.nanoTime() - started < 2_500_000_000L)
    }

    // ---- cover art: GET /v1/web/art/{emulator}/{key} ----

    @Test fun slash_and_unicode_key_path() = runBlocking {
        val key = "GBA/Pokémon - Emerald Version (USA).srm"
        server.enqueue(MockResponse.Builder().code(404).body("""{"code":"not_found"}""").build())
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"no_art"}""").build())
        val c = ApiClient(base(), "k")
        try { c.game("retro arch", key) } catch (_: ApiException) { }
        assertEquals(ArtResult.None, c.art("retro arch", key))
        val game = server.takeRequest().url.encodedPath
        val art = server.takeRequest()
        assertEquals(game.replace("/v1/web/games/", "/v1/web/art/"), art.url.encodedPath)
        assertEquals("/v1/web/art/retro%20arch/GBA/Pok%C3%A9mon%20-%20Emerald%20Version%20(USA).srm", art.url.encodedPath)
        assertEquals("1", art.headers["Tandem-Protocol"])
        assertEquals("Bearer k", art.headers["Authorization"])
    }

    @Test fun art_answers_map_to_results() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "image/png").body(okio.Buffer().write(png)).build())
        server.enqueue(MockResponse.Builder().code(202).setHeader("Retry-After", "3").body("""{"error":"art_pending"}""").build())
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"no_art"}""").build())
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())
        val c = ApiClient(base(), "k")
        val image = c.art("dolphin", "GSXE69")
        assertTrue(image is ArtResult.Image && image.bytes.contentEquals(png))
        assertEquals(ArtResult.Pending, c.art("dolphin", "GSXE69"))
        assertEquals(ArtResult.None, c.art("dolphin", "GSXE69"))
        assertEquals(ArtResult.Failed, c.art("dolphin", "GSXE69"))
    }

    @Test fun art_that_cannot_be_reached_is_a_failure_not_a_throw() = runBlocking {
        val c = ApiClient("http://127.0.0.1:1", "k")
        assertEquals(ArtResult.Failed, c.art("dolphin", "GSXE69"))
    }

    @Test fun art_for_a_revoked_key_reports_the_revoke() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body(revokedBody).build())
        var revoked = 0
        assertEquals(ArtResult.Failed, ApiClient(base(), "k", onRevoked = { revoked++ }).art("dolphin", "GSXE69"))
        assertEquals(1, revoked)
    }
}
