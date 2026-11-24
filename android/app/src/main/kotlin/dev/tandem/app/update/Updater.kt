package dev.tandem.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The largest APK the updater downloads. */
const val MAX_APK_BYTES = 150L * 1024 * 1024

/** GitHub's latest published release of [repo] ("owner/name"); drafts and prereleases are never "latest". */
fun latestReleaseUrl(repo: String) = "https://api.github.com/repos/$repo/releases/latest"

/** Where the updater is, for Settings › About. */
sealed interface UpdatePhase {
    /** A debug build: updates are off. */
    data object Off : UpdatePhase
    data object Unchecked : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    data class Downloading(val version: String) : UpdatePhase
    data class Ready(val version: String) : UpdatePhase
    data class Installing(val version: String) : UpdatePhase
    /** A whole sentence: "Couldn't check for updates: …", "0.3.0 wasn't installed: …". */
    data class Failed(val message: String) : UpdatePhase
}

data class UpdateUi(
    val phase: UpdatePhase = UpdatePhase.Unchecked,
    val lastCheckAt: Long? = null,
    val autoInstall: Boolean = true,
    /** A ready update installs as soon as sync is idle (automatic, or Install now was pressed). */
    val installQueued: Boolean = false,
    /** An update is downloaded and checked: Install now can install it. */
    val canInstall: Boolean = false,
)

sealed interface InstallOutcome {
    /** The install is under way (Shizuku's `pm install` said Success, or a session was committed). */
    data object Started : InstallOutcome
    /** Android's confirm prompt is up or posted as a notification: the user finishes it. */
    data object Prompted : InstallOutcome
    data class Failed(val message: String) : InstallOutcome
}

/** Hands a checked APK to Android. */
interface Installer {
    suspend fun install(apk: File, version: String, userAsked: Boolean): InstallOutcome
}

private class Refused(val reason: String) : Exception(reason)

/**
 * Checks GitHub for a newer release, downloads its APK into [dir], checks it ([verifyApk]) and keeps it ready; then
 * [install]s it when asked (the sync service asks once sync is idle). One check and one install run at a time. A
 * failure deletes what it downloaded and is shown until the next check that goes through.
 *
 * [onReadyToAsk] hears each newly ready version while automatic installs are off, so the user can be asked.
 */
class Updater(
    private val store: UpdateStore,
    private val dir: File,
    private val installedCode: Int,
    private val debug: Boolean,
    private val inspector: ApkInspector,
    private val latestUrl: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val onReadyToAsk: (version: String) -> Unit = {},
    private val maxBytes: Long = MAX_APK_BYTES,
    private val httpsOnly: Boolean = true,
) {
    private val checking = Mutex()
    private val installing = Mutex()
    @Volatile private var transient: UpdatePhase? = null
    /** The versionCode handed to Android this process: not handed again on its own. */
    @Volatile private var attempted: Int? = null
    private val state = MutableStateFlow(UpdateUi())
    val ui: StateFlow<UpdateUi> get() = state

    init {
        val r = store.data.value.ready
        if (r != null && r.versionCode <= installedCode) {
            log("update ${r.versionName} is installed")
            store.update { it.copy(ready = null, installWanted = false, failedInstall = null, lastError = null) }
        } else if (r != null && !File(r.path).isFile) {
            store.update { it.copy(ready = null, installWanted = false, failedInstall = null) }
        }
        if (store.data.value.ready == null) dir.listFiles()?.forEach { it.delete() }   // a download cut short
        publish()
    }

    @Synchronized
    private fun publish() {
        val d = store.data.value
        val phase = when {
            debug -> UpdatePhase.Off
            transient != null -> transient!!
            d.lastError != null -> UpdatePhase.Failed(d.lastError)
            d.ready != null -> UpdatePhase.Ready(d.ready.versionName)
            d.lastCheckAt != null -> UpdatePhase.UpToDate
            else -> UpdatePhase.Unchecked
        }
        state.value = UpdateUi(phase, d.lastCheckAt, d.autoInstall, installQueued = wantsInstall(), canInstall = !debug && d.ready != null)
    }

    private fun set(phase: UpdatePhase?) { transient = phase; publish() }

    private fun save(f: (UpdateData) -> UpdateData) { store.update(f); publish() }

    fun setAutoInstall(on: Boolean) = save { it.copy(autoInstall = on) }

    /** Install now (or the notification): install the ready update once sync is idle, even after a failure. */
    fun requestInstall() { if (store.data.value.ready != null) save { it.copy(installWanted = true) } }

    /** Whether a ready update should be installed now that sync is idle. */
    fun wantsInstall(): Boolean {
        val d = store.data.value
        val r = d.ready ?: return false
        return when {
            debug -> false
            d.installWanted -> true
            !d.autoInstall -> false
            else -> r.versionCode != d.failedInstall && r.versionCode != attempted
        }
    }

    /**
     * Asks GitHub when a check is due ([CheckSchedule]; [force] for Check now). A check counts when it is made, so a
     * failure waits for the next one. A check already running makes this a no-op.
     */
    suspend fun check(sessionStart: Boolean = false, force: Boolean = false) {
        if (debug) return
        if (!force && !CheckSchedule.due(clock(), store.data.value.lastCheckAt, sessionStart)) return
        if (!checking.tryLock()) return
        try {
            withContext(Dispatchers.IO) { runCheck() }
        } finally {
            checking.unlock()
            set(null)
        }
    }

    private fun runCheck() {
        store.update { it.copy(lastCheckAt = clock()) }
        set(UpdatePhase.Checking)
        val body = try {
            get(latestUrl, json = true, limit = 1024 * 1024)
        } catch (e: Refused) {
            return checkFailed(e.reason)
        }
        val release = when (val p = parseRelease(String(body), httpsOnly)) {
            is ReleaseParse.Invalid -> return checkFailed(p.reason)
            is ReleaseParse.Ok -> p.release
        }
        when (val d = decide(release, installedCode, debug)) {
            Decision.Off -> return
            Decision.UpToDate -> {
                store.data.value.ready?.let { File(it.path).delete() }
                store.update { it.copy(lastError = null, ready = null, installWanted = false, failedInstall = null) }
            }
            is Decision.Newer -> {
                val have = store.data.value.ready
                if (have != null && have.versionCode == release.versionCode && File(have.path).isFile) {
                    // Already downloaded: an install failure stays on screen, anything else clears.
                    store.update { if (it.failedInstall == have.versionCode) it else it.copy(lastError = null) }
                    return
                }
                download(d.release)
            }
        }
    }

    /** A check that failed. With an update already downloaded, that one stays on screen. */
    private fun checkFailed(reason: String) {
        log("update check failed: $reason")
        if (store.data.value.ready == null) store.update { it.copy(lastError = "Couldn't check for updates: $reason") }
    }

    private fun download(r: Release) {
        val name = r.versionName
        set(UpdatePhase.Downloading(name))
        store.data.value.ready?.let { File(it.path).delete() }
        store.update { it.copy(ready = null, installWanted = false, failedInstall = null) }
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "tandem-${r.versionCode}.apk")
        val part = File(dir, apk.name + ".part")
        fun fail(message: String) {
            part.delete(); apk.delete()
            log("update: $message")
            store.update { it.copy(lastError = message) }
        }
        if (r.apk.size > maxBytes) return fail("Couldn't download $name: it is larger than ${maxBytes / (1024 * 1024)} MB")
        try {
            val sha = parseSha256(String(get(r.sha256.url, json = false, limit = 4096)))
                ?: return fail("Couldn't download $name: its checksum file can't be read")
            getTo(r.apk.url, part, r.apk.size)
            if (part.length() != r.apk.size) return fail("Couldn't download $name: the download was cut short")
            if (!part.renameTo(apk)) return fail("Couldn't download $name: the file can't be saved")
            val verdict = verifyApk(sha, sha256Hex(apk), inspector.archive(apk), inspector.installed())
            if (verdict is ApkVerdict.Bad) return fail("$name wasn't installed: ${verdict.reason}")
            store.update {
                it.copy(ready = ReadyApk(apk.path, name, r.versionCode, sha), lastError = null, installWanted = false, failedInstall = null)
            }
            log("update $name downloaded and checked")
            if (!store.data.value.autoInstall) onReadyToAsk(name)
        } catch (e: Refused) {
            fail("Couldn't download $name: ${e.reason}")
        } catch (e: IOException) {
            fail("Couldn't download $name: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Installs the ready update through [installer]. The file is hashed again first: anything changed since the
     * check is deleted, never installed. Call only while sync is idle.
     */
    suspend fun install(installer: Installer) {
        if (debug) return
        val d = store.data.value
        val ready = d.ready ?: return
        if (!installing.tryLock()) return
        try {
            val file = File(ready.path)
            val sha = withContext(Dispatchers.IO) { if (file.isFile) sha256Hex(file) else null }
            if (sha == null) return save { it.copy(ready = null, installWanted = false) }
            if (sha != ready.sha256) {
                file.delete()
                log("update ${ready.versionName}: the file changed since it was checked")
                return save { it.copy(ready = null, installWanted = false, lastError = "${ready.versionName} wasn't installed: its checksum doesn't match $SHA_ASSET") }
            }
            attempted = ready.versionCode
            store.update { it.copy(installWanted = false, lastError = null, failedInstall = null) }
            set(UpdatePhase.Installing(ready.versionName))
            log("installing update ${ready.versionName}")
            when (val o = installer.install(file, ready.versionName, d.installWanted)) {
                InstallOutcome.Started -> publish()
                InstallOutcome.Prompted -> prompted()
                is InstallOutcome.Failed -> installFailed(o.message)
            }
        } finally {
            installing.unlock()
        }
    }

    /** Android's confirm prompt waits for the user: the update is ready, and not handed over again on its own. */
    fun prompted() = set(null)

    /** Android reported the install failed or was cancelled. */
    fun installFailed(message: String) {
        val ready = store.data.value.ready ?: return set(null)
        log("update ${ready.versionName} not installed: $message")
        transient = null
        save { it.copy(lastError = "Couldn't install ${ready.versionName}: $message", failedInstall = ready.versionCode, installWanted = false) }
    }

    // ---- http ----

    private fun request(url: String, json: Boolean): Request = Request.Builder().url(url)
        .header("Accept", if (json) "application/vnd.github+json" else "application/octet-stream")
        .header("User-Agent", "Tandem-Android-Updater")
        .build()

    private fun get(url: String, json: Boolean, limit: Int): ByteArray = try {
        http.newCall(request(url, json)).execute().use { resp ->
            if (!resp.isSuccessful) throw Refused(
                if (json && resp.code == 404) "no release is published yet" else "GitHub answered ${resp.code}",
            )
            val src = resp.body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            copyCapped(src, out, limit.toLong()) { throw Refused("GitHub's answer is too large") }
            out.toByteArray()
        }
    } catch (e: IOException) {
        throw Refused("GitHub can't be reached")
    }

    private fun getTo(url: String, to: File, size: Long) {
        val resp = try {
            http.newCall(request(url, json = false)).execute()
        } catch (e: IOException) {
            throw Refused("GitHub can't be reached")
        }
        resp.use {
            if (!it.isSuccessful) throw Refused("GitHub answered ${it.code}")
            to.outputStream().use { out ->
                copyCapped(it.body.byteStream(), out, minOf(size, maxBytes)) { throw Refused("it is larger than GitHub said") }
                out.fd.sync()
            }
        }
    }

    private inline fun copyCapped(src: java.io.InputStream, out: java.io.OutputStream, limit: Long, over: () -> Nothing) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) over()
            out.write(buf, 0, n)
        }
    }
}
