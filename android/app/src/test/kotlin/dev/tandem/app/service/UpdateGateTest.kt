package dev.tandem.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateGateTest {
    private val idle = SyncActivity(waitingWork = emptySet(), queuedUploads = 0, awaitingExit = emptySet(), heldDeliveries = 0)

    @Test fun nothingWaitingIsIdle() = assertNull(installBlocker(idle))

    @Test fun theTickersOwnWorkDoesNotCountAsBusy() {
        // Ticks and process probes are queued every few seconds whatever happens; they never hold an update back.
        assertNull(installBlocker(idle.copy(waitingWork = setOf(SyncWork.Tick, SyncWork.Monitor, SyncWork.InstallUpdate))))
    }

    @Test fun aQueuedScanOrApplyIsBusy() {
        assertEquals("a scan is queued", installBlocker(idle.copy(waitingWork = setOf(SyncWork.Tick, SyncWork.FullScan))))
        assertEquals("an apply is queued", installBlocker(idle.copy(waitingWork = setOf(SyncWork.ApplyAll))))
        assertEquals("an apply is queued", installBlocker(idle.copy(waitingWork = setOf(SyncWork.ApplyRequested))))
    }

    @Test fun anUploadNotYetSentIsBusy() {
        assertEquals("1 upload is waiting", installBlocker(idle.copy(queuedUploads = 1)))
        assertEquals("3 uploads are waiting", installBlocker(idle.copy(queuedUploads = 3)))
    }

    @Test fun anApplyWaitingForAnEmulatorToCloseIsBusy() {
        assertEquals("an apply waits for eden to close", installBlocker(idle.copy(awaitingExit = setOf("eden"))))
        assertEquals("2 deliveries wait for an emulator to close", installBlocker(idle.copy(heldDeliveries = 2)))
        assertEquals("1 delivery waits for an emulator to close", installBlocker(idle.copy(heldDeliveries = 1)))
    }
}
