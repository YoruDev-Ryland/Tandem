package dev.tandem.app.ui.art

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.tandem.app.api.ArtResult
import dev.tandem.app.ui.state.ShelfUi
import dev.tandem.app.ui.state.Spine
import dev.tandem.app.ui.screens.SpineImages
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Cover art for the shelf's card, kept on this device. Only images are written, to [dir] under a hashed name, up to
 * [maxBytes] in all, the least recently used going first (a read touches the file). The server's "no art" (404) is
 * remembered for this session only, in memory; "pending" (202) and a failed request are never remembered.
 *
 * [api] asks the server; [clock] and [delay] are the time it reads and the way it waits, so tests need not wait.
 */
/** What the cache holds for a game: its front cover, or its real case spine. */
enum class ArtKind { Cover, Spine }

class ArtCache(
    private val dir: File,
    private val api: suspend (emulatorId: String, gameKey: String) -> ArtResult,
    private val spineApi: suspend (emulatorId: String, gameKey: String) -> ArtResult = { _, _ -> ArtResult.None },
    private val maxBytes: Long = 50L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val misses: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    /** At most [MAX_REQUESTS] requests to the server at once, however many spines want art. */
    private val requests = Semaphore(MAX_REQUESTS)
    /** The last few covers, decoded, so going back and forth along the shelf doesn't read and decode again. */
    private val memory = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MEMORY_ITEMS
    }

    // A cover keeps the id it always had (so covers already on disk still count); a spine has its own.
    private fun id(emulatorId: String, gameKey: String, kind: ArtKind = ArtKind.Cover) =
        if (kind == ArtKind.Spine) "$emulatorId\n$gameKey\nspine" else "$emulatorId\n$gameKey"

    private fun file(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.img")
    }

    /** The cover if it is decoded in memory already: cheap enough to call while composing. */
    fun inMemory(emulatorId: String, gameKey: String, kind: ArtKind = ArtKind.Cover): ImageBitmap? =
        synchronized(memory) { memory[id(emulatorId, gameKey, kind)] }

    /** The cover if it is in memory or on disk, without asking the server. */
    fun cached(emulatorId: String, gameKey: String, kind: ArtKind = ArtKind.Cover): ImageBitmap? {
        val id = id(emulatorId, gameKey, kind)
        synchronized(memory) { memory[id] }?.let { return it }
        val f = file(id)
        if (!f.isFile) return null
        val img = decode(f.readBytes()) ?: run { f.delete(); return null }
        f.setLastModified(clock())
        synchronized(memory) { memory[id] = img }
        return img
    }

    /**
     * The cover: from memory or disk, else from the server unless it said this session that there is none. A pending
     * answer waits 3 s and asks once more; pending again gives up until the game is focused again. Never throws
     * (but is cancelled with its caller: a focus change drops the retry).
     */
    suspend fun load(emulatorId: String, gameKey: String, kind: ArtKind = ArtKind.Cover): ImageBitmap? {
        val id = id(emulatorId, gameKey, kind)
        withContext(Dispatchers.IO) { cached(emulatorId, gameKey, kind) }?.let { return it }
        if (id in misses) return null
        // A prefetch for this game is already asking: wait for it rather than ask (and write the file) twice.
        synchronized(inFlight) { inFlight[id]?.takeIf { it.isActive } }?.let { return it.await() }
        return fetch(emulatorId, gameKey, kind, id)
    }

    /** Whether the server said this session that there is nothing of [kind] for the game. */
    fun isMiss(emulatorId: String, gameKey: String, kind: ArtKind = ArtKind.Cover): Boolean = id(emulatorId, gameKey, kind) in misses

    private suspend fun fetch(emulatorId: String, gameKey: String, kind: ArtKind, id: String): ImageBitmap? {
        var r = ask(emulatorId, gameKey, kind)
        if (r == ArtResult.Pending) {
            delay(RETRY_MS)
            r = ask(emulatorId, gameKey, kind)
        }
        return when (r) {
            is ArtResult.Image -> withContext(Dispatchers.IO) { store(id, r.bytes) } ?: run { misses += id; null }
            ArtResult.None -> { misses += id; null }
            ArtResult.Pending, ArtResult.Failed -> null
        }
    }

    /** Loads [keys] (emulator id, game key, kind) in the background, for the games on and beside the shelf's view. */
    fun prefetch(keys: List<Triple<String, String, ArtKind>>) {
        for ((e, k, kind) in keys) {
            val id = id(e, k, kind)
            if (id in misses || synchronized(memory) { id in memory }) continue
            synchronized(inFlight) {
                if (inFlight[id]?.isActive == true) return@synchronized
                inFlight[id] = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        withContext(Dispatchers.IO) { cached(e, k, kind) } ?: if (id in misses) null else fetch(e, k, kind, id)
                    } finally {
                        synchronized(inFlight) { inFlight.remove(id) }
                    }
                }.also { it.start() }
            }
        }
    }

    private suspend fun ask(emulatorId: String, gameKey: String, kind: ArtKind): ArtResult = try {
        requests.withPermit { if (kind == ArtKind.Spine) spineApi(emulatorId, gameKey) else api(emulatorId, gameKey) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ArtResult.Failed
    }

    /** Writes the image and returns it decoded; null (and nothing written) when the bytes are not an image. */
    private fun store(id: String, bytes: ByteArray): ImageBitmap? {
        val img = decode(bytes) ?: return null
        runCatching {
            dir.mkdirs()
            val f = file(id)
            // A name of its own, so two writers of the same cover never share (or delete) each other's file.
            val tmp = File.createTempFile(f.name, PART, dir)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f) && tmp.exists()) {
                f.delete()
                if (!tmp.renameTo(f)) tmp.delete()
            }
            f.setLastModified(clock())
            evict()
        }
        synchronized(memory) { memory[id] = img }
        return img
    }

    /** Deletes the least recently used images until the folder is within [maxBytes]. */
    private fun evict() {
        // A part file an earlier process left behind (killed while writing).
        dir.listFiles { f -> f.isFile && f.name.endsWith(PART) && clock() - f.lastModified() > STALE_PART_MS }?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".img") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // The card's cover is at most 112 dp wide: about 310 px on the Thor; halve big images down towards that.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= DECODE_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap() }.getOrNull()
    }

    private companion object {
        const val RETRY_MS = 3000L
        const val PART = ".part"
        const val STALE_PART_MS = 60L * 60 * 1000
        const val MEMORY_ITEMS = 80
        const val MAX_REQUESTS = 4
        const val DECODE_WIDTH = 320
    }
}

/**
 * The shelf card's art slot: the cover of the focused game ([emulatorId], [gameKey]), shown from the cache at once when
 * it is there, else loaded; the games either side of it in [ui] are fetched in the background. Nothing is asked for
 * until the cursor has rested on the game for [SETTLE_MS] ([settle]), so holding the D-pad along the shelf sends no requests. A
 * focus change cancels the load (its request and its retry) for the game left behind.
 */
@Composable
fun shelfArt(
    cache: ArtCache,
    ui: ShelfUi,
    emulatorId: String,
    gameKey: String,
    settle: suspend () -> Unit = { kotlinx.coroutines.delay(SETTLE_MS) },
): ImageBitmap? {
    var img by remember(emulatorId, gameKey) { mutableStateOf(cache.inMemory(emulatorId, gameKey)) }
    LaunchedEffect(emulatorId, gameKey) {
        settle()
        val spines = ui.shelves.firstOrNull { it.tab.id == emulatorId }?.spines.orEmpty()
        val i = spines.indexOfFirst { it.gameKey == gameKey }
        if (i >= 0) cache.prefetch(listOfNotNull(spines.getOrNull(i - 1), spines.getOrNull(i + 1)).map { Triple(it.emulatorId, it.gameKey, ArtKind.Cover) })
        if (img == null) img = cache.load(emulatorId, gameKey)
    }
    return img
}

/** How long the cursor rests on a game before its art (and its neighbours') is asked for; the web waits as long. */
const val SETTLE_MS = 150L

/**
 * A shelf spine's images: its real case spine when the server has one, else its front cover; nothing for a Switch
 * spine, which is plain. Shown at once from memory, else loaded; the load stops when the spine leaves the shelf.
 */
@Composable
fun spineImages(cache: ArtCache, s: Spine): SpineImages {
    if (s.system?.uppercase() == "SWITCH") return SpineImages()
    var spine by remember(s.emulatorId, s.gameKey) { mutableStateOf(if (s.hasSpine) cache.inMemory(s.emulatorId, s.gameKey, ArtKind.Spine) else null) }
    var cover by remember(s.emulatorId, s.gameKey) { mutableStateOf(cache.inMemory(s.emulatorId, s.gameKey)) }
    LaunchedEffect(s.emulatorId, s.gameKey, s.hasSpine) {
        if (s.hasSpine && spine == null) spine = cache.load(s.emulatorId, s.gameKey, ArtKind.Spine)
        // A game with a real spine needs its cover only when the server has no spine for it after all.
        if (spine == null && cover == null) cover = cache.load(s.emulatorId, s.gameKey)
    }
    return SpineImages(spine, cover)
}

/**
 * Fetches, in the background, what the spines [first]..[last] draw from: the real spine for games that have one (and
 * the cover once the server said it has no spine), the cover for the rest. Switch spines need nothing.
 */
fun prefetchSpines(cache: ArtCache, spines: List<Spine>, first: Int, last: Int) {
    if (first > last) return
    val keys = spines.subList(first.coerceAtLeast(0), (last + 1).coerceAtMost(spines.size)).flatMap { s ->
        when {
            s.system?.uppercase() == "SWITCH" -> emptyList()
            s.hasSpine && !cache.isMiss(s.emulatorId, s.gameKey, ArtKind.Spine) -> listOf(Triple(s.emulatorId, s.gameKey, ArtKind.Spine))
            else -> listOf(Triple(s.emulatorId, s.gameKey, ArtKind.Cover))
        }
    }
    cache.prefetch(keys)
}
