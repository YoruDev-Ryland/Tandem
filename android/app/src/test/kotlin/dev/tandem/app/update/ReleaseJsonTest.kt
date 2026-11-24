package dev.tandem.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val APK_URL = "https://github.com/YoruDev-Ryland/Tandem/releases/download/v0.3.0/tandem.apk"
private const val SHA_URL = "https://github.com/YoruDev-Ryland/Tandem/releases/download/v0.3.0/tandem.apk.sha256"

/** A release as GitHub's /releases/latest returns it, trimmed to the fields that matter plus a few that don't. */
internal fun releaseJson(
    tag: String = "v0.3.0",
    assets: String = """
        {"name": "tandem.apk", "size": 12345678, "browser_download_url": "$APK_URL", "content_type": "application/vnd.android.package-archive"},
        {"name": "tandem.apk.sha256", "size": 77, "browser_download_url": "$SHA_URL"}
    """,
    draft: Boolean = false,
    prerelease: Boolean = false,
) = """{"url": "https://api.github.com/repos/x/y/releases/1", "id": 1, "tag_name": "$tag", "name": "Tandem $tag",
    "draft": $draft, "prerelease": $prerelease, "published_at": "2026-11-20T10:00:00Z", "assets": [$assets],
    "body": "notes", "author": {"login": "someone"}}"""

class ReleaseJsonTest {
    @Test fun aWellFormedReleaseGivesItsVersionAndBothAssets() {
        val r = (parseRelease(releaseJson()) as ReleaseParse.Ok).release
        assertEquals("v0.3.0", r.tag)
        assertEquals("0.3.0", r.versionName)
        assertEquals(3_000, r.versionCode)
        assertEquals(APK_URL, r.apk.url)
        assertEquals(12_345_678L, r.apk.size)
        assertEquals(SHA_URL, r.sha256.url)
    }

    @Test fun otherAssetsAlongsideAreFine() {
        val r = parseRelease(releaseJson(assets = """
            {"name": "server.tar.gz", "size": 1, "browser_download_url": "https://x/server.tar.gz"},
            {"name": "tandem.apk", "size": 10, "browser_download_url": "$APK_URL"},
            {"name": "tandem.apk.sha256", "size": 77, "browser_download_url": "$SHA_URL"}
        """))
        assertTrue(r is ReleaseParse.Ok)
    }

    @Test fun aMissingApkOrChecksumIsNotAnUpdate() {
        val noApk = parseRelease(releaseJson(assets = """{"name": "tandem.apk.sha256", "size": 77, "browser_download_url": "$SHA_URL"}"""))
        assertEquals(ReleaseParse.Invalid("the release has no tandem.apk"), noApk)
        val noSha = parseRelease(releaseJson(assets = """{"name": "tandem.apk", "size": 10, "browser_download_url": "$APK_URL"}"""))
        assertEquals(ReleaseParse.Invalid("the release has no tandem.apk.sha256"), noSha)
        assertEquals(ReleaseParse.Invalid("the release has no tandem.apk"), parseRelease(releaseJson(assets = "")))
    }

    @Test fun wronglyNamedAssetsDoNotCount() {
        val r = parseRelease(releaseJson(assets = """
            {"name": "tandem-v0.3.0.apk", "size": 10, "browser_download_url": "$APK_URL"},
            {"name": "Tandem.APK", "size": 10, "browser_download_url": "$APK_URL"},
            {"name": "tandem.apk.sha256", "size": 77, "browser_download_url": "$SHA_URL"}
        """))
        assertEquals(ReleaseParse.Invalid("the release has no tandem.apk"), r)
    }

    @Test fun anAssetWithoutAUsableLinkOrSizeIsMissing() {
        for (apk in listOf(
            """{"name": "tandem.apk", "size": 10}""",
            """{"name": "tandem.apk", "size": 10, "browser_download_url": ""}""",
            """{"name": "tandem.apk", "size": 10, "browser_download_url": "http://github.com/a/tandem.apk"}""",
            """{"name": "tandem.apk", "size": 0, "browser_download_url": "$APK_URL"}""",
            """{"name": "tandem.apk", "browser_download_url": "$APK_URL"}""",
        )) {
            val r = parseRelease(releaseJson(assets = """$apk, {"name": "tandem.apk.sha256", "size": 77, "browser_download_url": "$SHA_URL"}"""))
            assertEquals(apk, ReleaseParse.Invalid("the release has no tandem.apk"), r)
        }
    }

    @Test fun aTagThatIsNotAVersionIsRefused() {
        assertEquals(ReleaseParse.Invalid("the release tag \"nightly\" is not a version"), parseRelease(releaseJson(tag = "nightly")))
    }

    @Test fun draftsAndPrereleasesAreRefusedEvenIfServed() {
        assertTrue(parseRelease(releaseJson(draft = true)) is ReleaseParse.Invalid)
        assertTrue(parseRelease(releaseJson(prerelease = true)) is ReleaseParse.Invalid)
    }

    @Test fun unreadableJsonIsRefused() {
        assertEquals(ReleaseParse.Invalid("GitHub's answer could not be read"), parseRelease("<html>rate limited</html>"))
        assertEquals(ReleaseParse.Invalid("GitHub's answer could not be read"), parseRelease("""{"message": "Not Found"}"""))
        assertEquals(ReleaseParse.Invalid("GitHub's answer could not be read"), parseRelease("[]"))
    }

    @Test fun theChecksumIsTheFirstTokenOfTheShaFile() {
        val hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(hex, parseSha256(hex))
        assertEquals(hex, parseSha256("$hex  tandem.apk\n"))
        assertEquals(hex, parseSha256("\n  $hex\ttandem.apk"))
        assertEquals(hex, parseSha256(hex.uppercase() + " tandem.apk"))
    }

    @Test fun aChecksumThatIsNotSha256HexIsRefused() {
        assertNull(parseSha256(""))
        assertNull(parseSha256("   "))
        assertNull(parseSha256("abc123 tandem.apk"))
        assertNull(parseSha256("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a0"))     // 63
        assertNull(parseSha256("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a0899")) // 66
        assertNull(parseSha256("zf86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))
    }
}
