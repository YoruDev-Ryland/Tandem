package dev.tandem.app.shell

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellFsCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writeAtomic_replaces_file_and_leaves_no_tmp() {
        val f = File(tmp.root, "a.sav").apply { writeText("old") }
        ShellFsCore.writeAtomic(f.path, "new!".byteInputStream())
        assertEquals("new!", f.readText())
        assertEquals(listOf("a.sav"), tmp.root.list()!!.toList())
    }

    @Test fun writeAtomic_creates_parent_dirs() {
        val f = File(tmp.root, "x/y/z.sav")
        ShellFsCore.writeAtomic(f.path, "hi".byteInputStream())
        assertEquals("hi", f.readText())
    }

    @Test fun walk_returns_files_recursively_with_absolute_paths_sorted() {
        File(tmp.root, "b/c").mkdirs()
        File(tmp.root, "b/c/2.txt").writeText("22")
        File(tmp.root, "b/1.txt").writeText("1")
        File(tmp.root, "a.txt").writeText("a")
        val w = ShellFsCore.walk(tmp.root.path)
        assertEquals(listOf("a.txt", "b/1.txt", "b/c/2.txt").map { File(tmp.root, it).path }, w.map { it.path })
        assertTrue(w.none { it.isDir })
        assertEquals(2L, w.last().size)
    }

    @Test fun list_missing_dir_is_empty() {
        assertTrue(ShellFsCore.list(File(tmp.root, "nope").path).isEmpty())
        assertTrue(ShellFsCore.walk(File(tmp.root, "nope").path).isEmpty())
    }

    @Test fun list_includes_dirs_non_recursive() {
        File(tmp.root, "d").mkdirs(); File(tmp.root, "d/f").writeText("x"); File(tmp.root, "g").writeText("x")
        val l = ShellFsCore.list(tmp.root.path)
        assertEquals(listOf("d" to true, "g" to false), l.sortedBy { it.path }.map { File(it.path).name to it.isDir })
    }

    @Test fun delete_and_copy_work() {
        val a = File(tmp.root, "a").apply { writeText("data") }
        val b = File(tmp.root, "sub/b")
        ShellFsCore.copy(a.path, b.path)
        assertEquals("data", b.readText())
        assertTrue(ShellFsCore.delete(a.path))
        assertFalse(a.exists())
        assertFalse(ShellFsCore.delete(a.path))
        val d = File(tmp.root, "sub")
        assertTrue(ShellFsCore.delete(d.path))
        assertFalse(d.exists())
    }

    @Test fun stat_missing_is_null() {
        assertNull(ShellFsCore.stat(File(tmp.root, "q").path))
    }

    private class FailingStream(val n: Int) : java.io.InputStream() {
        var i = 0
        override fun read(): Int { if (i++ >= n) throw java.io.IOException("boom"); return 'x'.code }
    }

    @Test fun writeAtomic_source_failure_keeps_target_and_removes_tmp() {
        val f = File(tmp.root, "t.sav").apply { writeText("good") }
        try { ShellFsCore.writeAtomic(f.path, FailingStream(5)); fail() } catch (e: java.io.IOException) {}
        assertEquals("good", f.readText())
        assertEquals(listOf("t.sav"), tmp.root.list()!!.toList())
    }

    @Test fun writeAtomic_beforeCommit_failure_keeps_target() {
        val f = File(tmp.root, "t.sav").apply { writeText("good") }
        try {
            ShellFsCore.writeAtomic(f.path, "new".byteInputStream()) { throw java.io.IOException("abort") }
            fail()
        } catch (e: java.io.IOException) {}
        assertEquals("good", f.readText())
        assertEquals(listOf("t.sav"), tmp.root.list()!!.toList())
    }

    @Test fun tmp_files_are_hidden_from_list_and_walk() {
        File(tmp.root, "a.sav").writeText("1")
        File(tmp.root, "a.sav.tandem-tmp").writeText("2")
        File(tmp.root, "d").mkdirs(); File(tmp.root, "d/b.tandem-tmp").writeText("3")
        assertEquals(listOf("a.sav", "d"), ShellFsCore.list(tmp.root.path).map { File(it.path).name }.sorted())
        assertEquals(listOf("a.sav"), ShellFsCore.walk(tmp.root.path).map { File(it.path).name })
    }

    @Test fun listPage_pages_by_offset() {
        for (n in 1..5) File(tmp.root, "f$n").writeText("x")
        val p1 = ShellFsCore.listPage(tmp.root.path, 0, 2)
        val p2 = ShellFsCore.listPage(tmp.root.path, 2, 2)
        val p3 = ShellFsCore.listPage(tmp.root.path, 4, 2)
        assertEquals(listOf(2, 2, 1), listOf(p1.size, p2.size, p3.size))
        assertEquals(5, (p1 + p2 + p3).map { it.path }.toSet().size)
    }

    @Test fun walkPage_cursor_survives_mutation_between_pages() {
        for (n in 1..6) File(tmp.root, "f$n").writeText("x")
        val p1 = ShellFsCore.walkPage(tmp.root.path, "", 3)
        assertEquals(3, p1.size)
        // mutate: delete an already-returned file, add one before and one after the cursor
        File(tmp.root, "f1").delete()
        File(tmp.root, "f0").writeText("x")
        File(tmp.root, "f7").writeText("x")
        val p2 = ShellFsCore.walkPage(tmp.root.path, p1.last().path, 3)
        val p3 = ShellFsCore.walkPage(tmp.root.path, p2.last().path, 3)
        val all = (p1 + p2 + p3).map { File(it.path).name }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.containsAll(listOf("f2", "f3", "f4", "f5", "f6")))
        assertTrue(ShellFsCore.walkPage(tmp.root.path, p3.last().path, 3).isEmpty())
    }
}
