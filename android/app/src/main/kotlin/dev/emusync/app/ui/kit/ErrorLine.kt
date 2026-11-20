package dev.emusync.app.ui.kit

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.emusync.app.api.ApiException
import dev.emusync.app.ui.state.OfflineError
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu
import java.io.IOException

// The web UI's copy (web/src/lib/ui/errors.ts), so a code reads the same on both. protocol_mismatch says what to do
// in an app rather than "reload the page".
private val TEXT = mapOf(
    "conflict_open" to "This game has two saves waiting for a choice. Resolve that first.",
    "too_large" to "That file is too large for the server's upload limit.",
    "rate_limited" to "Too many attempts. Wait a minute and try again.",
    "device_revoked" to "This device was removed from your account. Pair it again to keep syncing.",
    "invalid_code" to "That code is not valid. Check it and try again.",
    "wrong_password" to "The current password is incorrect.",
    "weak_password" to "Use at least 8 characters for the new password.",
    "invalid_email" to "Enter a valid email address.",
    "email_taken" to "An account with this email already exists.",
    "cannot_delete_self" to "You can't delete your own account.",
    "backup_running" to "A backup is already running.",
    "protocol_mismatch" to "The server was updated. Update EmuSync on this device to continue.",
)

private const val LOST = "Can't reach the server. Check your connection and try again."

/** Human copy for an API error code, with the next step. */
fun errorText(code: String): String = TEXT[code] ?: "Something went wrong ($code). Try again."

/** Copy for anything a request can throw: API errors by code (413 as too large), a failed connection as offline. */
fun describeError(error: Throwable): String = when (error) {
    is OfflineError, is IOException -> LOST
    is ApiException -> errorText(if (error.status == 413) "too_large" else error.code ?: error.status.toString())
    else -> "Something went wrong. Try again."
}

/** A failed request, said in fault with what to do next. Announced when it appears. */
@Composable
fun ErrorLine(error: Throwable, modifier: Modifier = Modifier) {
    BasicText(
        describeError(error),
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = EmuType.body.copy(color = LocalEmu.current.fault),
    )
}
