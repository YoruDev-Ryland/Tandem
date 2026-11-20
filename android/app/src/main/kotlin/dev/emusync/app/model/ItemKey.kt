package dev.emusync.app.model

object ItemKey {
    fun of(emulatorId: String, kind: String, gameKey: String, slot: String, core: String) =
        "$emulatorId|$kind|$gameKey|$slot|$core"

    fun safe(key: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
}
