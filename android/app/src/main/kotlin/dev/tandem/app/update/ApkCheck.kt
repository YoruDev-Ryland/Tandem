package dev.tandem.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/** What Android reads from an APK (or the installed app): its package, versionCode and signers (SHA-256 hex each). */
data class ApkInfo(val packageName: String, val versionCode: Long, val signers: Set<String>)

sealed interface ApkVerdict {
    data object Ok : ApkVerdict
    /** Not installed; [reason] follows "… wasn't installed: ". */
    data class Bad(val reason: String) : ApkVerdict
}

/**
 * Whether a downloaded APK may be installed over [installed]: its SHA-256 ([actualSha]) is the release's
 * ([expectedSha]), and Android reads it ([archive]) as the same package, a higher versionCode and exactly the same
 * signing certificates. An installed app with no readable signers fails closed.
 */
fun verifyApk(expectedSha: String, actualSha: String, archive: ApkInfo?, installed: ApkInfo): ApkVerdict = when {
    !expectedSha.equals(actualSha, ignoreCase = true) -> ApkVerdict.Bad("its checksum doesn't match $SHA_ASSET")
    archive == null -> ApkVerdict.Bad("Android can't read it as an app")
    archive.packageName != installed.packageName -> ApkVerdict.Bad("it is another app (${archive.packageName})")
    archive.versionCode <= installed.versionCode -> ApkVerdict.Bad("it is not newer than the installed version")
    archive.signers.isEmpty() -> ApkVerdict.Bad("it isn't signed")
    installed.signers.isEmpty() || archive.signers != installed.signers -> ApkVerdict.Bad("it is signed with another key")
    else -> ApkVerdict.Ok
}

/** Lowercase hex SHA-256 of [file]. */
fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Reads [ApkInfo] for a file and for this app. */
interface ApkInspector {
    fun archive(file: File): ApkInfo?
    fun installed(): ApkInfo
}

/** [ApkInspector] over the PackageManager, with GET_SIGNING_CERTIFICATES. */
class PackageManagerInspector(context: Context) : ApkInspector {
    private val ctx = context.applicationContext
    private val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    override fun archive(file: File): ApkInfo? = runCatching {
        ctx.packageManager.getPackageArchiveInfo(file.path, flags)?.info()
    }.getOrNull()

    override fun installed(): ApkInfo = ctx.packageManager.getPackageInfo(ctx.packageName, flags).info()

    private fun PackageInfo.info(): ApkInfo {
        // The certificates the APK's contents are signed with now (the current key after a rotation).
        val certs = signingInfo?.apkContentsSigners.orEmpty()
        val md = { b: ByteArray -> MessageDigest.getInstance("SHA-256").digest(b).toHex() }
        return ApkInfo(packageName, longVersionCode, certs.map { md(it.toByteArray()) }.toSet())
    }
}
