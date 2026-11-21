package dev.tandem.app.service

import dev.tandem.app.fs.ShizukuState

/**
 * The persistent notification's text. [watching] is the number of detected emulators, [queued] the uploads that
 * failed and wait for the next scan. [online] is the server event stream's state; [signedIn] whether a device key
 * and server URL are stored.
 */
fun statusText(
    state: ShizukuState,
    watching: Int,
    queued: Int,
    online: Boolean = true,
    signedIn: Boolean = true,
    revoked: Boolean = false,
): String = when {
    signedIn && revoked -> "Removed from the server: open Tandem"
    state == ShizukuState.NotRunning -> "Shizuku is off: open Tandem"
    state == ShizukuState.NoPermission -> "Shizuku permission needed: open Tandem"
    !signedIn -> "Not signed in: open Tandem"
    !online -> "Offline: ${changes(queued)} queued"
    watching == 0 -> "No emulators found" + (if (queued > 0) ", ${changes(queued)} queued" else "")
    else -> "Watching $watching emulator${if (watching == 1) "" else "s"}" +
        (if (queued > 0) ", ${changes(queued)} queued" else "")
}

private fun changes(n: Int) = if (n == 1) "1 change" else "$n changes"
