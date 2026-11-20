package dev.emusync.app.modules

import org.junit.Assert.*
import org.junit.Test

class ContentUrisTest {
    private val base = "content://com.android.externalstorage.documents/tree/"

    @Test fun content_uri_primary_and_sd_map_to_paths() {
        assertEquals("/storage/emulated/0/A/B", ContentUris.toPath(base + "primary%3AA%2FB"))
        assertEquals("/storage/emulated/0/Games/switch", ContentUris.toPath("\"" + base + "primary%3AGames%2Fswitch\""))
        assertEquals("/storage/ABCD-1234/A", ContentUris.toPath(base + "ABCD-1234%3AA"))
        assertNull(ContentUris.toPath("content://other/tree/primary%3AA"))
        assertNull(ContentUris.toPath("/storage/emulated/0/A"))
        assertNull(ContentUris.toPath(base + "weird%3AA"))
    }

    @Test fun canonical_maps_sdcard() {
        assertEquals("/storage/emulated/0/x", Paths.canonical("/sdcard/x"))
        assertEquals("/storage/ABCD-1234/x", Paths.canonical("/storage/ABCD-1234/x"))
    }
}
