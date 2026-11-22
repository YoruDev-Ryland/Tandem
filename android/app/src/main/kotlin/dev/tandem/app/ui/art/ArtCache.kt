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
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Cover art for the shelf's card, kept on this device. Only images are written, to [dir] under a hashed name, up to
 * [maxBytes] in all, the least recently used going first (a read touches the file). The server's "no art" (404) is
 * remembered for this session only, in memory; "pending" (202) and a failed request are never remembered.
 *
 * [api] asks the server; [clock] and [delay] are the time it reads and the way it waits, so tests need not wait.
 */
class ArtCache(
    private val dir: File,
    private val api: suspend (emulatorId: String, gameKey: String) -> ArtResult,
    private val maxBytes: Long = 50L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val misses: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    /** The last few covers, decoded, so going back and forth along the shelf doesn't read and decode again. */
    private val memory = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MEMORY_ITEMS
    }

    private fun id(emulatorId: String, gameKey: String) = "$emulatorId\n$gameKey"

    private fun file(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.img")
    }

    /** The cover if it is decoded in memory already: cheap enough to call while composing. */
    fun inMemory(emulatorId: String, gameKey: String): ImageBitmap? = synchronized(memory) { memory[id(emulatorId, gameKey)] }

    /** The cover if it is in memory or on disk, without asking the server. */
    fun cached(emulatorId: String, gameKey: String): ImageBitmap? {
        val id = id(emulatorId, gameKey)
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
    suspend fun load(emulatorId: String, gameKey: String): ImageBitmap? {
        val id = id(emulatorId, gameKey)
        withContext(Dispatchers.IO) { cached(emulatorId, gameKey) }?.let { return it }
        if (id in misses) return null
        var r = ask(emulatorId, gameKey)
        if (r == ArtResult.Pending) {
            delay(RETRY_MS)
            r = ask(emulatorId, gameKey)
        }
        return when (r) {
            is ArtResult.Image -> withContext(Dispatchers.IO) { store(id, r.bytes) } ?: run { misses += id; null }
            ArtResult.None -> { misses += id; null }
            ArtResult.Pending, ArtResult.Failed -> null
        }
    }

    /** Loads [keys] (emulator id, game key) in the background, for the games beside the cursor. */
    fun prefetch(keys: List<Pair<String, String>>) {
        for ((e, k) in keys) {
            val id = id(e, k)
            if (id in misses || synchronized(memory) { id in memory }) continue
            synchronized(inFlight) {
                if (inFlight[id]?.isActive == true) return@synchronized
                inFlight[id] = scope.async { try { load(e, k) } finally { synchronized(inFlight) { inFlight.remove(id) } } }
            }
        }
    }

    private suspend fun ask(emulatorId: String, gameKey: String): ArtResult = try {
        api(emulatorId, gameKey)
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
            val tmp = File(dir, f.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            f.setLastModified(clock())
            evict()
        }
        synchronized(memory) { memory[id] = img }
        return img
    }

    /** Deletes the least recently used images until the folder is within [maxBytes]. */
    private fun evict() {
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
        const val MEMORY_ITEMS = 12
        const val DECODE_WIDTH = 320
    }
}

/**
 * The shelf card's art slot: the cover of the focused game ([emulatorId], [gameKey]), shown from the cache at once when
 * it is there, else loaded; the games either side of it in [ui] are fetched in the background. A focus change cancels
 * the load (and its retry) for the game left behind.
 */
@Composable
fun shelfArt(cache: ArtCache, ui: ShelfUi, emulatorId: String, gameKey: String): ImageBitmap? {
    var img by remember(emulatorId, gameKey) { mutableStateOf(cache.inMemory(emulatorId, gameKey)) }
    LaunchedEffect(emulatorId, gameKey) {
        val spines = ui.shelves.firstOrNull { it.tab.id == emulatorId }?.spines.orEmpty()
        val i = spines.indexOfFirst { it.gameKey == gameKey }
        if (i >= 0) cache.prefetch(listOfNotNull(spines.getOrNull(i - 1), spines.getOrNull(i + 1)).map { it.emulatorId to it.gameKey })
        if (img == null) img = cache.load(emulatorId, gameKey)
    }
    return img
}
