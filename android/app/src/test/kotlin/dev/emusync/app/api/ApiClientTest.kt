package dev.emusync.app.api

import dev.emusync.app.model.ItemKind
import dev.emusync.app.model.ManifestEntry
import dev.emusync.app.model.SaveUploadRequest
import dev.emusync.app.model.SaveUploadStatus
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
        assertEquals("1", r.headers["EmuSync-Protocol"])
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

    @Test fun timed_status_put_fails_instead_of_landing_late() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).headersDelay(3, TimeUnit.SECONDS).build())
        val req = dev.emusync.app.model.DeviceStatusRequest("android", "0", true, emptyList(), emptyMap(), listOf("eden"))
        val started = System.nanoTime()
        try {
            ApiClient(base(), "k").putStatus(req, callTimeoutMillis = 300)
            fail("expected the call timeout")
        } catch (e: java.io.IOException) {
            // OkHttp's call timeout cancels the call: it fails rather than completing after the server answers
        }
        assertTrue("cancelled by the call timeout, not the 3 s answer", System.nanoTime() - started < 2_500_000_000L)
    }
}
