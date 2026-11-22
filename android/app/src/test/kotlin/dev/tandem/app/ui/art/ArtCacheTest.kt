package dev.tandem.app.ui.art

import dev.tandem.app.api.ArtResult
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** A tiny generated PNG, [w]×[h], [noise] making it incompressible so its size can be set. Never real cover art. */
private fun png(w: Int = 4, h: Int = 6, noise: Boolean = false): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val r = java.util.Random(w * 31L + h)
    for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, if (noise) r.nextInt() else 0x3C6E68)
    return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtCacheTest {
    private val dir: File = Files.createTempDirectory("art").toFile().also { it.deleteOnExit() }
    private val calls = mutableListOf<Pair<String, String>>()
    private val delays = mutableListOf<Long>()
    private var now = 1_000_000L
    private val answers = ArrayDeque<() -> ArtResult>()

    private fun cache(maxBytes: Long = 50L * 1024 * 1024) = ArtCache(
        dir = dir,
        api = { e, k -> calls += e to k; answers.removeFirstOrNull()?.invoke() ?: ArtResult.None },
        maxBytes = maxBytes,
        clock = { now },
        delay = { delays += it },
    )

    private fun answer(vararg r: ArtResult) { for (x in r) answers.addLast { x } }

    @Test fun image_is_stored_and_served_from_disk() = runBlocking {
        answer(ArtResult.Image(png()))
        val c = cache()
        val first = c.load("dolphin", "GSXE69")
        assertNotNull(first)
        assertEquals(4, first!!.width)
        assertEquals(1, calls.size)
        // A new cache over the same folder (the next launch) reads it from disk: no request.
        val again = cache().load("dolphin", "GSXE69")
        assertNotNull(again)
        assertEquals(1, calls.size)
        assertNotNull(cache().cached("dolphin", "GSXE69"))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun pending_retries_once_after_three_seconds() = runBlocking {
        answer(ArtResult.Pending, ArtResult.Image(png()))
        assertNotNull(cache().load("dolphin", "GSXE69"))
        assertEquals(2, calls.size)
        assertEquals(listOf(3000L), delays)
    }

    @Test fun pending_twice_gives_null_and_is_not_a_session_miss() = runBlocking {
        answer(ArtResult.Pending, ArtResult.Pending)
        val c = cache()
        assertNull(c.load("dolphin", "GSXE69"))
        assertEquals(2, calls.size)
        answer(ArtResult.Image(png()))
        assertNotNull(c.load("dolphin", "GSXE69"))   // focused again: asked again
        assertEquals(3, calls.size)
    }

    @Test fun none_is_a_session_miss_not_on_disk() = runBlocking {
        answer(ArtResult.None)
        val c = cache()
        assertNull(c.load("dolphin", "GSXE69"))
        assertNull(c.load("dolphin", "GSXE69"))
        assertEquals(1, calls.size)
        assertEquals(0, dir.listFiles()!!.size)
        // Only for this session: a new cache (the next launch) asks again.
        cache().load("dolphin", "GSXE69")
        assertEquals(2, calls.size)
    }

    @Test fun network_error_is_none() = runBlocking {
        answers.addLast { throw IOException("unreachable") }
        val c = cache()
        assertNull(c.load("dolphin", "GSXE69"))
        // Not a miss: the server never said there is no art, so the next focus asks again.
        answer(ArtResult.Image(png()))
        assertNotNull(c.load("dolphin", "GSXE69"))
        assertEquals(2, calls.size)
    }

    @Test fun a_failed_answer_is_not_remembered() = runBlocking {
        answer(ArtResult.Failed, ArtResult.Image(png()))
        val c = cache()
        assertNull(c.load("dolphin", "GSXE69"))
        assertNotNull(c.load("dolphin", "GSXE69"))
    }

    @Test fun bytes_that_are_not_an_image_are_a_miss_and_not_stored() = runBlocking {
        answer(ArtResult.Image("<html>no</html>".toByteArray()))
        val c = cache()
        assertNull(c.load("dolphin", "GSXE69"))
        assertEquals(0, dir.listFiles()!!.size)
        assertNull(c.load("dolphin", "GSXE69"))
        assertEquals(1, calls.size)
    }

    @Test fun evicts_least_recently_used_over_cap() = runBlocking {
        val a = png(40, 40, noise = true)
        val cap = a.size * 2L + a.size / 2   // room for two
        answer(ArtResult.Image(a), ArtResult.Image(png(40, 40, noise = true)), ArtResult.Image(png(41, 40, noise = true)))
        val c = cache(cap)
        assertNotNull(c.load("e", "one")); now += 1000
        assertNotNull(c.load("e", "two")); now += 1000
        assertNotNull(cache(cap).load("e", "one")); now += 1000   // read from disk: "one" is now the newer of the two
        assertNotNull(c.load("e", "three"))
        assertEquals(2, dir.listFiles()!!.size)
        val fresh = cache(cap)
        assertNotNull(fresh.cached("e", "one"))
        assertNotNull(fresh.cached("e", "three"))
        assertNull(fresh.cached("e", "two"))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= cap)
    }

    @Test fun keys_with_slashes_and_unicode_get_flat_hashed_names() = runBlocking {
        answer(ArtResult.Image(png()))
        assertNotNull(cache().load("retroarch", "GBA/Pokémon - Emerald Version (USA).srm"))
        val name = dir.listFiles()!!.single().name
        assertTrue(name, Regex("^[0-9a-f]{64}\\.img$").matches(name))
    }

    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.v2.createComposeRule()

    /** Lets the slot's loads (which read the disk off the main thread) run until [done]. */
    private fun settle(done: () -> Boolean) {
        repeat(100) {
            compose.waitForIdle()
            if (done()) return
            Thread.sleep(20)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
    }

    @Test fun the_slot_shows_the_cover_and_a_focus_change_drops_the_retry() {
        val cancelled = mutableListOf<Boolean>()
        val c = ArtCache(dir, api = { e, k -> calls += e to k; if (k == "GSXE69") ArtResult.Pending else ArtResult.Image(png()) }, clock = { now },
            delay = { try { kotlinx.coroutines.awaitCancellation() } finally { cancelled += true } })
        val ui = dev.tandem.app.ui.screens.shelfUi(emptyList(), dev.tandem.app.ui.state.Loaded(emptyList()), emptyList(), "d", true)
        val key = androidx.compose.runtime.mutableStateOf("GSXE69")
        var shown: androidx.compose.ui.graphics.ImageBitmap? = null
        compose.setContent { shown = shelfArt(c, ui, "dolphin", key.value) }
        settle { calls.isNotEmpty() }
        assertEquals(listOf("dolphin" to "GSXE69"), calls)   // pending: now waiting to ask again
        assertNull(shown)
        key.value = "GM8E01"
        settle { shown != null }
        assertNotNull(shown)
        assertEquals(listOf(true), cancelled)                 // the old game's retry went with its focus
        assertEquals(listOf("dolphin" to "GSXE69", "dolphin" to "GM8E01"), calls)
    }
}
