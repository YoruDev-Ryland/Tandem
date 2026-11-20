package dev.emusync.app.service

import dev.emusync.app.fs.ShizukuState

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
): String = when {
    state == ShizukuState.NotRunning -> "Shizuku is off: open EmuSync"
    state == ShizukuState.NoPermission -> "Shizuku permission needed: open EmuSync"
    !signedIn -> "Not signed in: open EmuSync"
    !online -> "Offline: ${changes(queued)} queued"
    watching == 0 -> "No emulators found" + (if (queued > 0) ", ${changes(queued)} queued" else "")
    else -> "Watching $watching emulator${if (watching == 1) "" else "s"}" +
        (if (queued > 0) ", ${changes(queued)} queued" else "")
}

private fun changes(n: Int) = if (n == 1) "1 change" else "$n changes"
