package dev.emusync.app.shell

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

    @Test fun walk_returns_files_recursively_with_relative_paths_sorted() {
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
}
