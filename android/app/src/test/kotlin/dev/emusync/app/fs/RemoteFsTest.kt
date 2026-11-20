package dev.emusync.app.fs

import dev.emusync.app.shell.ErrCodec
import dev.emusync.app.shell.IShellService
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RemoteFsTest {
    /** A shell service whose every call throws [t]. */
    private fun throwing(t: Throwable): IShellService =
        Proxy.newProxyInstance(IShellService::class.java.classLoader, arrayOf(IShellService::class.java)) { _, _, _ -> throw t } as IShellService

    private fun statThrows(t: Throwable): Throwable =
        try { RemoteFs(throwing(t)).stat("/x"); fail("expected an exception"); error("unreachable") } catch (e: Exception) { e }

    @Test fun security_exception_becomes_io_exception() {
        val e = statThrows(SecurityException("denied"))
        assertTrue(e.javaClass.name, e is IOException)
    }

    @Test fun other_runtime_exceptions_become_io_exception() {
        for (t in listOf(IllegalArgumentException("a"), NullPointerException("b"), IllegalStateException("c"),
                UnsupportedOperationException("not ours"), RuntimeException("d"))) {
            val e = statThrows(t)
            assertTrue("${t.javaClass.simpleName} -> ${e.javaClass.name}", e is IOException)
        }
    }

    @Test fun coded_errors_keep_their_meaning() {
        assertTrue(statThrows(ErrCodec.encode(ErrCodec.NOT_FOUND, "gone")) is FileNotFoundException)
        assertTrue(statThrows(ErrCodec.encode(ErrCodec.PROBE, "p")) is FsUnavailableException)
        assertTrue(statThrows(ErrCodec.encode(ErrCodec.IO, "io")) is IOException)
    }
}
