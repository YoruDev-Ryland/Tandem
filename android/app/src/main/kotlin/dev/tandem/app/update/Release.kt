package dev.tandem.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The APK asset of every release. */
const val APK_ASSET = "tandem.apk"

/** Its checksum: the first whitespace-separated token is the lowercase hex SHA-256 of [APK_ASSET]. */
const val SHA_ASSET = "tandem.apk.sha256"

data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** A published release of the app, as GitHub's `releases/latest` describes it. */
data class Release(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val apk: ReleaseAsset,
    val sha256: ReleaseAsset,
)

sealed interface ReleaseParse {
    data class Ok(val release: Release) : ReleaseParse
    /** Not a release this app can update to, and why (shown after "Couldn't check for updates: "). */
    data class Invalid(val reason: String) : ReleaseParse
}

@Serializable
private class GhAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
)

@Serializable
private class GhRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Reads GitHub's answer for the latest release. Only the exact asset names count; a link must be https ([httpsOnly]
 * is off only for tests against a local server).
 */
fun parseRelease(body: String, httpsOnly: Boolean = true): ReleaseParse {
    val gh = try {
        json.decodeFromString<GhRelease>(body)
    } catch (_: Exception) {
        return ReleaseParse.Invalid("GitHub's answer could not be read")
    }
    if (gh.draft || gh.prerelease) return ReleaseParse.Invalid("the latest release is a draft or a prerelease")
    val code = ReleaseTag.versionCode(gh.tag) ?: return ReleaseParse.Invalid("the release tag \"${gh.tag}\" is not a version")
    fun asset(name: String) = gh.assets.firstOrNull { it.name == name && it.size > 0 && (it.url.startsWith("https://") || !httpsOnly && it.url.startsWith("http://")) }
        ?.let { ReleaseAsset(it.name, it.url, it.size) }
    val apk = asset(APK_ASSET) ?: return ReleaseParse.Invalid("the release has no $APK_ASSET")
    val sha = asset(SHA_ASSET) ?: return ReleaseParse.Invalid("the release has no $SHA_ASSET")
    return ReleaseParse.Ok(Release(gh.tag, gh.tag.removePrefix("v"), code, apk, sha))
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/** The checksum in a `.sha256` file (its first token, as `sha256sum` writes it), lowercased; null when it is none. */
fun parseSha256(text: String): String? =
    text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { SHA256_HEX.matches(it) }
