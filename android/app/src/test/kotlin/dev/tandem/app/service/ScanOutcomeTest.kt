package dev.tandem.app.service

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only a full scan that finished with every upload through counts as done for whoever waits on it (setup step 5). */
class ScanOutcomeTest {
    @Test fun aCleanScanSucceeds() = assertTrue(scanSucceeded(error = null, queued = 0))
    @Test fun aScanThatThrewFails() = assertFalse(scanSucceeded(error = IOException("down"), queued = 0))
    @Test fun aScanWithQueuedUploadsFails() = assertFalse(scanSucceeded(error = null, queued = 2))

    @Test fun theCounterKeepsSuccessesAndFailuresApart() {
        val ok = AppGraph.fullScans.get()
        val bad = AppGraph.failedScans.get()
        countScan(succeeded = false)
        assertTrue(AppGraph.failedScans.get() == bad + 1 && AppGraph.fullScans.get() == ok)
        countScan(succeeded = true)
        assertTrue(AppGraph.fullScans.get() == ok + 1 && AppGraph.failedScans.get() == bad + 1)
    }
}
