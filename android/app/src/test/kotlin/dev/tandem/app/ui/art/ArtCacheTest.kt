package dev.tandem.app.ui.art

import dev.tandem.app.api.ArtResult
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.async
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

    private val spineCalls = mutableListOf<Pair<String, String>>()
    private val spineAnswers = ArrayDeque<() -> ArtResult>()

    private fun cache(maxBytes: Long = 50L * 1024 * 1024) = ArtCache(
        dir = dir,
        api = { e, k -> calls += e to k; answers.removeFirstOrNull()?.invoke() ?: ArtResult.None },
        spineApi = { e, k -> spineCalls += e to k; spineAnswers.removeFirstOrNull()?.invoke() ?: ArtResult.None },
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

    @Test fun pending_is_asked_again_until_it_resolves() = runBlocking {
        answer(ArtResult.Pending, ArtResult.Pending, ArtResult.Pending, ArtResult.Pending, ArtResult.Image(png()))
        assertNotNull(cache().load("dolphin", "GSXE69"))
        assertEquals(5, calls.size)
        assertEquals(List(4) { 3000L }, delays)
    }

    @Test fun pending_ten_times_gives_null_and_is_not_a_session_miss() = runBlocking {
        val c = cache()
        answer(*Array(10) { ArtResult.Pending })
        assertNull(c.load("dolphin", "GSXE69"))
        assertEquals(10, calls.size)
        answer(ArtResult.Image(png()))
        assertNotNull(c.load("dolphin", "GSXE69"))   // wanted again: asked again
        assertEquals(11, calls.size)
    }

    @Test fun a_prefetch_joins_a_running_load() = runBlocking {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val c = ArtCache(dir, api = { _, _ -> asked.incrementAndGet(); gate.await(); ArtResult.Image(png()) }, clock = { now }, delay = {})
        val loading = async(kotlinx.coroutines.Dispatchers.IO) { c.load("dolphin", "GSXE69") }
        kotlinx.coroutines.withTimeout(5000) { while (asked.get() == 0) kotlinx.coroutines.delay(5) }
        c.prefetch(listOf(Triple("dolphin", "GSXE69", ArtKind.Cover)))
        kotlinx.coroutines.delay(100)
        gate.complete(Unit)
        assertNotNull(loading.await())
        assertEquals(1, asked.get())
    }

    @Test fun memory_is_bounded_by_bytes() = runBlocking {
        // 20×20 ARGB images are 1600 bytes each; room for three.
        val c = ArtCache(dir, api = { _, _ -> ArtResult.Image(png(20, 20)) }, clock = { now }, delay = {}, memoryBytes = 3 * 1600)
        for (i in 1..5) c.load("retroarch", "g$i")
        assertNull(c.inMemory("retroarch", "g1"))
        assertNull(c.inMemory("retroarch", "g2"))
        assertNotNull(c.inMemory("retroarch", "g5"))
        assertNotNull(c.inMemory("retroarch", "g3"))
    }

    @Test fun a_real_spine_game_loads_its_cover_only_after_no_spine() {
        val spineAsks = mutableListOf<String>()
        val c = ArtCache(dir, api = { _, k -> calls += "dolphin" to k; ArtResult.Image(png()) },
            spineApi = { _, k -> spineAsks += k; if (k == "PENDING") ArtResult.Pending else ArtResult.None }, clock = { now }, delay = {})
        fun spine(key: String) = dev.tandem.app.ui.state.Spine("dolphin", key, key, false, "GC", emptyList(),
            dev.tandem.app.ui.state.ShelfCard("", "", false, "", dev.tandem.app.ui.kit.Tone.Ink2, emptyList()), hasSpine = true)
        var pending: dev.tandem.app.ui.screens.SpineImages? = null
        var none: dev.tandem.app.ui.screens.SpineImages? = null
        compose.setContent { pending = spineImages(c, spine("PENDING")); none = spineImages(c, spine("NOSPINE")) }
        settle { none?.cover != null && spineAsks.count { it == "PENDING" } >= 10 }
        assertNull(pending?.cover)                                 // still pending: no cover fetched for it
        assertEquals(listOf("dolphin" to "NOSPINE"), calls)        // no spine: the cover, once
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
        compose.setContent { shown = shelfArt(c, ui, "dolphin", key.value, settle = {}) }
        settle { calls.isNotEmpty() }
        assertEquals(listOf("dolphin" to "GSXE69"), calls)   // pending: now waiting to ask again
        assertNull(shown)
        key.value = "GM8E01"
        settle { shown != null }
        assertNotNull(shown)
        assertEquals(listOf(true), cancelled)                 // the old game's retry went with its focus
        assertEquals(listOf("dolphin" to "GSXE69", "dolphin" to "GM8E01"), calls)
    }

    @Test fun a_load_joins_the_prefetch_of_the_same_game() = runBlocking {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val c = ArtCache(dir, api = { _, _ -> asked.incrementAndGet(); gate.await(); ArtResult.Image(png()) }, clock = { now }, delay = {})
        c.prefetch(listOf(Triple("dolphin", "GSXE69", ArtKind.Cover)))
        kotlinx.coroutines.withTimeout(5000) { while (asked.get() == 0) kotlinx.coroutines.delay(5) }
        val loading = async { c.load("dolphin", "GSXE69") }
        kotlinx.coroutines.delay(50)
        gate.complete(Unit)
        assertNotNull(loading.await())
        assertEquals(1, asked.get())                       // one request, not one per caller
        assertEquals(listOf(".img"), dir.listFiles()!!.map { "." + it.name.substringAfterLast('.') })
    }

    @Test fun a_part_file_left_behind_is_cleared() = runBlocking {
        now = 10L * 60 * 60 * 1000
        dir.mkdirs()
        val old = File(dir, "abc.img123.part").apply { writeBytes(byteArrayOf(1)); setLastModified(1000) }
        val fresh = File(dir, "def.img456.part").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 1000) }
        answer(ArtResult.Image(png()))
        assertNotNull(cache().load("dolphin", "GSXE69"))
        assertTrue(!old.exists())
        assertTrue(fresh.exists())                         // may still be another writer's
    }

    @Test fun the_slot_asks_only_once_the_cursor_rests() {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val c = ArtCache(dir, api = { _, k -> asked += k; ArtResult.Image(png()) }, clock = { now })
        val ui = dev.tandem.app.ui.screens.shelfUi(emptyList(), dev.tandem.app.ui.state.Loaded(emptyList()), emptyList(), "d", true)
        val key = androidx.compose.runtime.mutableStateOf("GSXE69")
        val rested = HashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
        val waits = mutableListOf<String>()
        var shown: androidx.compose.ui.graphics.ImageBitmap? = null
        compose.setContent {
            val k = key.value
            shown = shelfArt(c, ui, "dolphin", k, settle = { waits += k; rested.getOrPut(k) { kotlinx.coroutines.CompletableDeferred() }.await() })
        }
        for (k in listOf("GM8E01", "GR8E69", "GALE01")) {
            compose.waitForIdle()
            key.value = k
        }
        settle { waits.size == 4 }
        assertEquals(listOf("GSXE69", "GM8E01", "GR8E69", "GALE01"), waits)
        rested.values.forEach { it.complete(Unit) }        // too late for the games passed over: their waits were cancelled
        settle { shown != null }
        assertEquals(listOf("GALE01"), asked.toList())     // passing over games asks for none of them
    }

    @Test fun spine_kind_uses_the_spine_path_and_its_own_entry() = runBlocking {
        val c = cache()
        answer(ArtResult.Image(png(4, 6)))
        spineAnswers.addLast { ArtResult.Image(png(2, 20)) }
        val cover = c.load("dolphin", "GXBE69")!!
        val spine = c.load("dolphin", "GXBE69", ArtKind.Spine)!!
        assertEquals(listOf("dolphin" to "GXBE69"), calls)
        assertEquals(listOf("dolphin" to "GXBE69"), spineCalls)
        assertEquals(4 to 6, cover.width to cover.height)
        assertEquals(2 to 20, spine.width to spine.height)
        assertEquals(2, c.inMemory("dolphin", "GXBE69", ArtKind.Spine)!!.width)
        assertEquals(4, c.inMemory("dolphin", "GXBE69")!!.width)
    }

    @Test fun prefetch_runs_at_most_four_at_once() = runBlocking {
        var now = 0; var peak = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val c = ArtCache(
            dir = dir,
            api = { _, _ ->
                synchronized(this@ArtCacheTest) { now++; peak = maxOf(peak, now) }
                gate.await()
                synchronized(this@ArtCacheTest) { now-- }
                ArtResult.None
            },
            clock = { 0L }, delay = {},
        )
        c.prefetch((1..10).map { Triple("retroarch", "g$it", ArtKind.Cover) })
        kotlinx.coroutines.delay(300)
        assertTrue("peak $peak", peak in 1..4)
        gate.complete(Unit)
        Unit
    }
}
