package dev.tandem.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkCheckTest {
    private val sha = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    private val installed = ApkInfo("dev.tandem.app", 2_000, setOf("aa11"))
    private val good = ApkInfo("dev.tandem.app", 3_000, setOf("aa11"))

    private fun check(actual: String = sha, archive: ApkInfo? = good, inst: ApkInfo = installed) =
        verifyApk(expectedSha = sha, actualSha = actual, archive = archive, installed = inst)

    @Test fun theRightFileFromTheSameKeyPasses() = assertEquals(ApkVerdict.Ok, check())

    @Test fun aChecksumMismatchFails() =
        assertEquals(ApkVerdict.Bad("its checksum doesn't match $SHA_ASSET"), check(actual = "0".repeat(64)))

    @Test fun theChecksumIsComparedWithoutCaring() =
        assertEquals(ApkVerdict.Ok, verifyApk(sha.uppercase(), sha, good, installed))

    @Test fun aFileAndroidCannotReadFails() =
        assertEquals(ApkVerdict.Bad("Android can't read it as an app"), check(archive = null))

    @Test fun anotherPackageFails() =
        assertEquals(ApkVerdict.Bad("it is another app (com.evil.app)"), check(archive = good.copy(packageName = "com.evil.app")))

    @Test fun theSameOrAnOlderVersionFails() {
        assertEquals(ApkVerdict.Bad("it is not newer than the installed version"), check(archive = good.copy(versionCode = 2_000)))
        assertEquals(ApkVerdict.Bad("it is not newer than the installed version"), check(archive = good.copy(versionCode = 1)))
    }

    @Test fun anUnsignedApkFails() =
        assertEquals(ApkVerdict.Bad("it isn't signed"), check(archive = good.copy(signers = emptySet())))

    @Test fun anotherKeyFails() {
        assertEquals(ApkVerdict.Bad("it is signed with another key"), check(archive = good.copy(signers = setOf("bb22"))))
        // An extra signer, or one missing, is not the same signing either.
        assertEquals(ApkVerdict.Bad("it is signed with another key"), check(archive = good.copy(signers = setOf("aa11", "bb22"))))
        assertEquals(ApkVerdict.Bad("it is signed with another key"),
            check(inst = installed.copy(signers = setOf("aa11", "bb22"))))
    }

    @Test fun anInstalledAppWithNoKnownSignersFailsClosed() =
        assertEquals(ApkVerdict.Bad("it is signed with another key"), check(inst = installed.copy(signers = emptySet())))
}
