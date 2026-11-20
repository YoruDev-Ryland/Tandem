package dev.emusync.app.shell

import org.junit.Assert.*
import org.junit.Test

class WatchTreeTest {
    @Test fun remove_subtree_frees_entries() {
        val t = WatchTree()
        listOf("/a", "/a/b", "/a/b/c", "/ab", "/z").forEach { assertTrue(t.add(it)) }
        val gone = t.removeSubtree("/a")
        assertEquals(setOf("/a", "/a/b", "/a/b/c"), gone.toSet())
        assertEquals(2, t.size)
        assertTrue(t.contains("/ab") && t.contains("/z"))
    }

    @Test fun recreated_dir_is_rewatched() {
        val t = WatchTree()
        assertTrue(t.add("/a")); assertFalse(t.add("/a"))
        t.removeSubtree("/a")
        assertTrue(t.add("/a"))
    }

    @Test fun cap_4000_enforced() {
        val t = WatchTree(4000)
        for (i in 0 until 4000) assertTrue(t.add("/d$i"))
        assertFalse(t.add("/extra"))
        assertEquals(4000, t.size)
        t.removeSubtree("/d0")
        assertTrue(t.add("/extra"))
    }
}
