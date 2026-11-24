package dev.tandem.app.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private val apkBytes = "PK fake apk bytes".repeat(100).toByteArray()
    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private var now = 1_000_000_000L
    private val installed = ApkInfo("dev.tandem.app", 2_005, setOf("cert"))
    private var archive: ApkInfo? = ApkInfo("dev.tandem.app", 3_000, setOf("cert"))
    private val asked = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    private val inspector = object : ApkInspector {
        override fun archive(file: File) = archive
        override fun installed() = installed
    }

    private val dir by lazy { File(tmp.root, "cache/update") }
    private val storeFile by lazy { File(tmp.root, "update.json") }

    private fun updater(debug: Boolean = false, installedCode: Int = 2_005, maxBytes: Long = MAX_APK_BYTES) = Updater(
        store = UpdateStore(storeFile), dir = dir, installedCode = installedCode, debug = debug, inspector = inspector,
        latestUrl = server.url("/repos/o/r/releases/latest").toString(), clock = { now }, log = { logs += it },
        onReadyToAsk = { asked += it }, maxBytes = maxBytes, httpsOnly = false,
    )

    // MockWebServer speaks http; the parser wants https links. These tests let the updater fetch http.
    private fun enqueueReleaseHttp(tag: String = "v0.3.0", apkSize: Long = apkBytes.size.toLong()) {
        server.enqueue(MockResponse.Builder().code(200).body(releaseJson(tag, """
            {"name": "tandem.apk", "size": $apkSize, "browser_download_url": "${server.url("/dl/tandem.apk")}"},
            {"name": "tandem.apk.sha256", "size": 77, "browser_download_url": "${server.url("/dl/tandem.apk.sha256")}"}
        """)).build())
    }

    private fun enqueueFiles(sha: String = hex(apkBytes) + "  tandem.apk\n", apk: ByteArray = apkBytes) {
        server.enqueue(MockResponse.Builder().code(200).body(sha).build())
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(apk)).build())
    }

    private fun Updater.checkNow() = runBlocking { check(force = true) }

    @Test fun aNewerReleaseIsDownloadedCheckedAndReady() {
        enqueueReleaseHttp(); enqueueFiles()
        val u = updater()
        u.checkNow()
        assertEquals(UpdatePhase.Ready("0.3.0"), u.ui.value.phase)
        val req = server.takeRequest()
        assertEquals("/repos/o/r/releases/latest", req.url.encodedPath)
        assertEquals("application/vnd.github+json", req.headers["Accept"])
        assertNull(req.headers["Authorization"])
        val ready = UpdateStore(storeFile).data.value.ready
        assertNotNull(ready); ready!!
        assertEquals(3_000, ready.versionCode)
        assertTrue(File(ready.path).readBytes().contentEquals(apkBytes))
        assertEquals(now, u.ui.value.lastCheckAt)
        assertEquals(listOf("tandem-3000.apk"), dir.list()!!.toList())
    }

    @Test fun theSameVersionIsUpToDateAndDownloadsNothing() {
        enqueueReleaseHttp()
        val u = updater(installedCode = 3_000)
        u.checkNow()
        assertEquals(UpdatePhase.UpToDate, u.ui.value.phase)
        assertEquals(1, server.requestCount)
    }

    @Test fun aDebugBuildNeverAsksGitHub() {
        val u = updater(debug = true)
        u.checkNow()
        assertEquals(UpdatePhase.Off, u.ui.value.phase)
        assertEquals(0, server.requestCount)
        assertFalse(u.wantsInstall())
    }

    @Test fun aChecksumMismatchDeletesTheFileAndSaysWhy() {
        enqueueReleaseHttp(); enqueueFiles(sha = "0".repeat(64))
        val u = updater()
        u.checkNow()
        assertEquals(UpdatePhase.Failed("0.3.0 wasn't installed: its checksum doesn't match tandem.apk.sha256"), u.ui.value.phase)
        assertTrue(dir.list().orEmpty().isEmpty())
        assertNull(UpdateStore(storeFile).data.value.ready)
        assertFalse(u.wantsInstall())
    }

    @Test fun anUnreadableChecksumFileFails() {
        enqueueReleaseHttp(); enqueueFiles(sha = "<html>oops</html>")
        val u = updater()
        u.checkNow()
        assertEquals(UpdatePhase.Failed("Couldn't download 0.3.0: its checksum file can't be read"), u.ui.value.phase)
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test fun anApkSignedWithAnotherKeyIsDeleted() {
        archive = ApkInfo("dev.tandem.app", 3_000, setOf("other"))
        enqueueReleaseHttp(); enqueueFiles()
        val u = updater()
        u.checkNow()
        assertEquals(UpdatePhase.Failed("0.3.0 wasn't installed: it is signed with another key"), u.ui.value.phase)
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test fun anApkOfAnotherPackageOrOlderVersionIsDeleted() {
        archive = ApkInfo("com.other", 3_000, setOf("cert"))
        enqueueReleaseHttp(); enqueueFiles()
        updater().also { it.checkNow() }.let {
            assertEquals(UpdatePhase.Failed("0.3.0 wasn't installed: it is another app (com.other)"), it.ui.value.phase)
        }
        archive = ApkInfo("dev.tandem.app", 2_005, setOf("cert"))
        enqueueReleaseHttp(); enqueueFiles()
        updater().also { it.checkNow() }.let {
            assertEquals(UpdatePhase.Failed("0.3.0 wasn't installed: it is not newer than the installed version"), it.ui.value.phase)
        }
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test fun aReleaseLargerThanTheCapIsNotDownloaded() {
        enqueueReleaseHttp(apkSize = 200L * 1024 * 1024)
        val u = updater()
        u.checkNow()
        assertEquals(UpdatePhase.Failed("Couldn't download 0.3.0: it is larger than 150 MB"), u.ui.value.phase)
        assertEquals(1, server.requestCount)
    }

    @Test fun aDownloadThatRunsPastItsSizeIsCutAndDeleted() {
        // GitHub said 10 bytes; the server sends more: the download stops, nothing is kept.
        enqueueReleaseHttp(apkSize = 10); enqueueFiles()
        val u = updater()
        u.checkNow()
        assertEquals(UpdatePhase.Failed("Couldn't download 0.3.0: it is larger than GitHub said"), u.ui.value.phase)
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test fun anHttpErrorIsShownAndTheNextCheckWaitsItsTurn() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("""{"message":"API rate limit exceeded"}""").build())
        val u = updater()
        u.check(force = true)
        assertEquals(UpdatePhase.Failed("Couldn't check for updates: GitHub answered 403"), u.ui.value.phase)
        assertEquals(now, u.ui.value.lastCheckAt)
        now += 60_000
        u.check(sessionStart = true)   // a session restart a minute later: not due
        u.check()
        assertEquals(1, server.requestCount)
        now += CheckSchedule.INTERVAL_MS
        enqueueReleaseHttp(); enqueueFiles()
        u.check()
        assertEquals(UpdatePhase.Ready("0.3.0"), u.ui.value.phase)
    }

    @Test fun noReleaseYetIsAPlainReason() {
        server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
        val u = updater()
        u.checkNow()
        assertEquals(UpdatePhase.Failed("Couldn't check for updates: no release is published yet"), u.ui.value.phase)
    }

    @Test fun aBrokenReleaseSaysWhatIsWrong() {
        server.enqueue(MockResponse.Builder().code(200).body(releaseJson(assets = "")).build())
        val u = updater()
        u.checkNow()
        assertEquals(UpdatePhase.Failed("Couldn't check for updates: the release has no tandem.apk"), u.ui.value.phase)
    }

    @Test fun aReadyUpdateIsNotDownloadedAgain() {
        enqueueReleaseHttp(); enqueueFiles()
        val u = updater()
        u.checkNow()
        enqueueReleaseHttp()
        u.checkNow()
        assertEquals(4, server.requestCount)   // release, sha, apk, then the release alone
        assertEquals(UpdatePhase.Ready("0.3.0"), u.ui.value.phase)
    }

    @Test fun aNewerReleaseReplacesAReadyOlderOne() {
        enqueueReleaseHttp(); enqueueFiles()
        val u = updater()
        u.checkNow()
        archive = ApkInfo("dev.tandem.app", 4_000, setOf("cert"))
        enqueueReleaseHttp(tag = "v0.4.0"); enqueueFiles()
        u.checkNow()
        assertEquals(UpdatePhase.Ready("0.4.0"), u.ui.value.phase)
        assertEquals(listOf("tandem-4000.apk"), dir.list()!!.toList())
    }

    @Test fun theStateSurvivesARestart() {
        enqueueReleaseHttp(); enqueueFiles()
        updater().checkNow()
        val again = updater()
        assertEquals(UpdatePhase.Ready("0.3.0"), again.ui.value.phase)
        assertEquals(now, again.ui.value.lastCheckAt)
    }

    @Test fun afterTheUpdateInstalledTheReadyFileIsDropped() {
        enqueueReleaseHttp(); enqueueFiles()
        updater().checkNow()
        val after = updater(installedCode = 3_000)   // the process came back as 0.3.0
        assertNull(UpdateStore(storeFile).data.value.ready)
        assertTrue(dir.list().orEmpty().isEmpty())
        assertEquals(UpdatePhase.UpToDate, after.ui.value.phase)
    }

    @Test fun aReadyFileTheSystemClearedIsForgotten() {
        enqueueReleaseHttp(); enqueueFiles()
        updater().checkNow()
        dir.deleteRecursively()
        val u = updater()
        assertNull(UpdateStore(storeFile).data.value.ready)
        assertFalse(u.wantsInstall())
    }

    // ---- installing ----

    private class FakeInstaller(var outcome: InstallOutcome = InstallOutcome.Started) : Installer {
        val calls = mutableListOf<Pair<File, Boolean>>()
        override suspend fun install(apk: File, version: String, userAsked: Boolean): InstallOutcome {
            calls += apk to userAsked
            return outcome
        }
    }

    private fun readyUpdater(auto: Boolean = true): Updater {
        enqueueReleaseHttp(); enqueueFiles()
        return updater().also { it.setAutoInstall(auto); it.checkNow() }
    }

    @Test fun withAutoInstallOnAReadyUpdateWantsInstalling() {
        val u = readyUpdater()
        assertTrue(u.wantsInstall())
        assertTrue(asked.isEmpty())   // nothing to ask: it installs when sync is idle
        val inst = FakeInstaller()
        runBlocking { u.install(inst) }
        assertEquals(1, inst.calls.size)
        assertEquals("tandem-3000.apk", inst.calls[0].first.name)
        assertFalse(inst.calls[0].second)
        assertEquals(UpdatePhase.Installing("0.3.0"), u.ui.value.phase)
    }

    @Test fun withAutoInstallOffItAsksAndWaitsForInstallNow() {
        val u = readyUpdater(auto = false)
        assertEquals(listOf("0.3.0"), asked)
        assertFalse(u.wantsInstall())
        u.requestInstall()
        assertTrue(u.wantsInstall())
        val inst = FakeInstaller()
        runBlocking { u.install(inst) }
        assertTrue(inst.calls.single().second)   // the user asked: the system prompt may open at once
    }

    @Test fun aFailedInstallIsShownAndNotRetriedUntilAsked() {
        val u = readyUpdater()
        val inst = FakeInstaller(InstallOutcome.Failed("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
        runBlocking { u.install(inst) }
        assertEquals(UpdatePhase.Failed("Couldn't install 0.3.0: INSTALL_FAILED_INSUFFICIENT_STORAGE"), u.ui.value.phase)
        assertFalse(u.wantsInstall())   // no retry every few seconds
        u.requestInstall()
        assertTrue(u.wantsInstall())
    }

    @Test fun aSystemPromptIsNotPostedAgainAndAgain() {
        val u = readyUpdater()
        runBlocking { u.install(FakeInstaller(InstallOutcome.Prompted)) }
        assertEquals(UpdatePhase.Ready("0.3.0"), u.ui.value.phase)
        assertFalse(u.wantsInstall())
        u.requestInstall()
        assertTrue(u.wantsInstall())
    }

    @Test fun aReadyFileChangedSinceTheCheckIsNotInstalled() {
        val u = readyUpdater()
        File(dir, "tandem-3000.apk").appendBytes(byteArrayOf(1))
        val inst = FakeInstaller()
        runBlocking { u.install(inst) }
        assertTrue(inst.calls.isEmpty())
        assertEquals(UpdatePhase.Failed("0.3.0 wasn't installed: its checksum doesn't match tandem.apk.sha256"), u.ui.value.phase)
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test fun theInstallerReportingFailureLaterIsShown() {
        val u = readyUpdater()
        runBlocking { u.install(FakeInstaller(InstallOutcome.Prompted)) }
        u.installFailed("the install was cancelled")
        assertEquals(UpdatePhase.Failed("Couldn't install 0.3.0: the install was cancelled"), u.ui.value.phase)
    }

    @Test fun theAutoInstallSwitchIsKept() {
        val u = updater()
        assertTrue(u.ui.value.autoInstall)
        u.setAutoInstall(false)
        assertFalse(updater().ui.value.autoInstall)
    }
}
