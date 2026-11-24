package dev.tandem.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellInstallTest {
    @Test fun pmIsAskedToReplaceWithTheSizeOnStdin() =
        assertEquals(listOf("pm", "install", "-r", "-S", "1234"), ShellInstall.command(1234))

    @Test fun theApkGoesToTheCommandsStdinAndItsOutputComesBack() {
        val out = ShellInstall.run("hello apk".byteInputStream(), listOf("sh", "-c", "wc -c; echo Success"))
        assertEquals("9\nSuccess", out.lines().map { it.trim() }.joinToString("\n"))
    }

    @Test fun stderrIsPartOfTheOutput() {
        val out = ShellInstall.run("".byteInputStream(), listOf("sh", "-c", "echo 'Failure [X]' >&2; exit 1"))
        assertEquals("Failure [X]", out)
    }

    @Test fun successIsALineSayingSo() {
        assertNull(pmFailure("Success"))
        assertNull(pmFailure("Performing Streamed Install\nSuccess\n"))
    }

    @Test fun aFailureGivesPmsReason() {
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package dev.tandem.app signatures do not match",
            pmFailure("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package dev.tandem.app signatures do not match]"))
        assertEquals("Error: something odd", pmFailure("Error: something odd\n"))
        assertEquals("pm printed nothing", pmFailure(""))
        assertEquals("pm printed nothing", pmFailure(null))
    }
}
