package dev.emusync.app.shell

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessProbeParseTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun pm_list_parses_packages() {
        val out = "package:com.a\r\npackage:dev.b.c\nWarning: junk\n\npackage:\n"
        assertEquals(setOf("com.a", "dev.b.c"), ProbeParse.parsePmList(out))
        assertTrue(ProbeParse.parsePmList("").isEmpty())
    }

    @Test fun any_running_matches_exact_and_subprocess_only() {
        val pkgs = listOf("dev.eden.eden_emulator", "dev.igawa6.edenduo")
        assertTrue(ProbeParse.anyRunning(listOf("init", "dev.igawa6.edenduo"), pkgs))
        assertTrue(ProbeParse.anyRunning(listOf("dev.eden.eden_emulator:emu"), pkgs))
        assertFalse(ProbeParse.anyRunning(listOf("dev.eden.eden_emulatorx", "dev.eden.eden_emulator.combo"), pkgs))
        assertFalse(ProbeParse.anyRunning(listOf(":dev.eden.eden_emulator", ""), pkgs))
        assertFalse(ProbeParse.anyRunning(listOf("x"), emptyList()))
    }

    @Test fun alive_reply_mapping_fails_closed_on_unknown_values() {
        assertEquals(true, ProbeParse.decodeAlive(ProbeParse.encodeAlive(true)))
        assertEquals(false, ProbeParse.decodeAlive(ProbeParse.encodeAlive(false)))
        for (v in listOf(0, -1, 3, 42)) assertNull("reply $v", ProbeParse.decodeAlive(v))
    }

    @Test fun process_name_is_first_cmdline_argument() {
        assertEquals("com.a:b", ProbeParse.processName("com.a:b\u0000\u0000".toByteArray()))
        assertEquals("/system/bin/init", ProbeParse.processName("/system/bin/init\u0000second_stage\u0000".toByteArray()))
        assertEquals("", ProbeParse.processName(ByteArray(0)))
    }

    private fun proc(vararg procs: Pair<String, String>): File {
        val root = tmp.newFolder("proc")
        File(root, "self").mkdirs(); File(root, "meminfo").writeText("x")
        for ((pid, cmd) in procs) File(root, pid).apply { mkdirs(); File(this, "cmdline").writeText(cmd) }
        return root
    }

    @Test fun proc_scan_finds_variants_and_subprocesses() {
        val root = proc("1" to "/system/bin/init\u0000second_stage\u0000", "200" to "dev.igawa6.edenduo:emu\u0000", "7" to "")
        assertEquals(setOf("/system/bin/init", "dev.igawa6.edenduo:emu"), ProcessProbe.processNames(root).toSet())
        assertTrue(ProcessProbe.isAnyProcessAlive(listOf("dev.eden.eden_emulator", "dev.igawa6.edenduo"), root))
        assertFalse(ProcessProbe.isAnyProcessAlive(listOf("dev.eden.eden_emulator"), root))
    }

    @Test fun proc_scan_fails_closed_without_pid_1() {
        val root = proc("200" to "other\u0000")
        try { ProcessProbe.processNames(root); fail("expected ProbeException") } catch (e: ProbeException) {}
    }

    @Test fun proc_scan_fails_closed_when_proc_unlistable() {
        try { ProcessProbe.processNames(File(tmp.root, "missing")); fail("expected ProbeException") } catch (e: ProbeException) {}
    }

    @Test fun proc_scan_fails_closed_on_unreadable_live_process() {
        val root = proc("1" to "init\u0000")
        File(root, "300").mkdirs()   // exists, but its cmdline can't be read
        try { ProcessProbe.processNames(root); fail("expected ProbeException") } catch (e: ProbeException) {}
    }
}
