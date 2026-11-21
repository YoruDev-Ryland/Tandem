package dev.tandem.app.service

import dev.tandem.app.model.DeviceStatusRequest
import dev.tandem.app.sync.DeliveryApplierTest
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The last accepted status (resent as "Shizuku off") is recorded only while its session is live. */
class StatusRecordingApiTest {
    private val req = DeviceStatusRequest("android", "0", true, emptyList(), emptyMap(), listOf("eden"))

    @Test fun records_accepted_status_of_the_live_session() = runBlocking {
        val flows = SessionFlows()
        val recorded = mutableListOf<DeviceStatusRequest>()
        val api = StatusRecordingApi(DeliveryApplierTest.FakeApi(), flows, flows.begin()) { recorded += it }
        api.putStatus(req)
        api.putStatus(req, callTimeoutMillis = 10_000)
        assertEquals(listOf(req, req), recorded)
    }

    @Test(timeout = 10_000L) fun a_put_returning_after_session_end_is_not_recorded() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : dev.tandem.app.api.Api by DeliveryApplierTest.FakeApi() {
            override suspend fun putStatus(req: DeviceStatusRequest) { entered.countDown(); release.await() }
            override suspend fun putStatus(req: DeviceStatusRequest, callTimeoutMillis: Long) = putStatus(req)
        }
        val flows = SessionFlows()
        val token = flows.begin()
        val recorded = mutableListOf<DeviceStatusRequest>()
        val api = StatusRecordingApi(slow, flows, token) { recorded += it }
        val put = async(Dispatchers.IO) { api.putStatus(req, callTimeoutMillis = 10_000) }
        entered.await()
        flows.end(token)   // session over; reportShizukuOff may already have used the last status
        release.countDown()
        put.await()
        assertTrue(recorded.isEmpty())
    }
}
