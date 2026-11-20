package dev.emusync.app.ui.kit

import dev.emusync.app.api.ApiException
import dev.emusync.app.ui.state.OfflineError
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorLineTest {
    @Test fun knownCodesHaveTheWebCopy() {
        assertEquals("This game has two saves waiting for a choice. Resolve that first.", errorText("conflict_open"))
        assertEquals("That file is too large for the server's upload limit.", errorText("too_large"))
        assertEquals("Too many attempts. Wait a minute and try again.", errorText("rate_limited"))
        assertEquals("This device was removed from your account. Pair it again to keep syncing.", errorText("device_revoked"))
        assertEquals("That code is not valid. Check it and try again.", errorText("invalid_code"))
    }

    @Test fun anUnknownCodeNamesItself() {
        assertEquals("Something went wrong (teapot). Try again.", errorText("teapot"))
    }

    @Test fun describesApiErrorsByCodeAnd413AsTooLarge() {
        assertEquals(errorText("rate_limited"), describeError(ApiException(429, "rate_limited", "x")))
        assertEquals(errorText("too_large"), describeError(ApiException(413, null, "Payload Too Large")))
        assertEquals("Something went wrong (500). Try again.", describeError(ApiException(500, null, "boom")))
    }

    @Test fun offlineAndIoAreALostConnection() {
        val lost = "Can't reach the server. Check your connection and try again."
        assertEquals(lost, describeError(OfflineError("timeout")))
        assertEquals(lost, describeError(java.io.IOException("reset")))
    }
}
