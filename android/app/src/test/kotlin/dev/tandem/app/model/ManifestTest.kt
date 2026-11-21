package dev.tandem.app.model

import dev.tandem.app.fs.LocalFs
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManifestTest {
    @get:Rule val tmp = TemporaryFolder()
    private val h = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881"

    @Test fun manifest_hash_matches_server_formula() {
        val m = listOf(ManifestEntry("b/z.sav", h, 1), ManifestEntry("a.sav", h, 1))
        assertEquals("bfdf80e8cfe758974d7e02daa38198a59b5bec2ffd2092b4f4274a8e2bec5b09", Manifests.hash(m))
    }

    @Test fun manifest_sorted_ordinal() {
        File(tmp.root, "b").mkdirs()
        File(tmp.root, "b/z.sav").writeText("x")
        File(tmp.root, "a.sav").writeText("x")
        File(tmp.root, "B.sav").writeText("x")
        val m = Manifests.build(LocalFs(), tmp.root.path, listOf("b/z.sav", "a.sav", "B.sav"))
        assertEquals(listOf("B.sav", "a.sav", "b/z.sav"), m.map { it.path })
        assertEquals(h, m[0].hash)
        assertEquals(1L, m[0].size)
    }

    @Test fun build_rejects_unsafe_paths() {
        for (bad in listOf("../x", "a/../x", "/abs", "\\x")) {
            assertThrows(IllegalArgumentException::class.java) {
                Manifests.build(LocalFs(), tmp.root.path, listOf(bad))
            }
        }
    }

    @Test fun uploadable_rules() {
        assertFalse(Manifests.isUploadable(emptyList()))
        assertTrue(Manifests.isUploadable(listOf(ManifestEntry("a", h, 1))))
        assertFalse(Manifests.isUploadable(List(10001) { ManifestEntry("a$it", h, 1) }))
    }

    @Test fun item_key_safe() {
        val k = ItemKey.of("e", "Save", "g", "s", "c")
        assertEquals("e|Save|g|s|c", k)
        assertEquals(16, ItemKey.safe(k).length)
        assertEquals(ItemKey.safe(k), ItemKey.safe(k))
    }
}
