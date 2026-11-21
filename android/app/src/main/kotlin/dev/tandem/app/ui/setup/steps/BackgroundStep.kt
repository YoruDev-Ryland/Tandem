package dev.tandem.app.ui.setup.steps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.kit.Hint
import dev.tandem.app.ui.setup.OptionText
import dev.tandem.app.ui.setup.Options
import dev.tandem.app.ui.setup.SetupFrame
import dev.tandem.app.ui.setup.SetupStep
import dev.tandem.app.ui.setup.SetupViewModel
import dev.tandem.app.ui.setup.StepNote
import dev.tandem.app.ui.setup.StepSub
import dev.tandem.app.ui.setup.StepTitle
import dev.tandem.app.ui.state.PermissionRequest
import dev.tandem.app.ui.state.TopStatus

const val NOTIFICATIONS_TITLE = "Allow notifications"
const val NOTIFICATIONS_DETAIL = "So Tandem can tell you about conflicts, and when to close an emulator to get a newer save."
const val BATTERY_TITLE = "Let Tandem run in the background"
const val BATTERY_DETAIL = "Turns off battery optimisation for Tandem, so the system doesn't stop sync while you play."
const val BATTERY_NOTE =
    "Some handhelds (AYN, Retroid and others) also stop apps on their own. If saves stop syncing after a while, " +
        "allow Tandem to auto-start in the device's own settings; dontkillmyapp.com has the steps per brand."

private sealed interface BackgroundRow {
    data object Notifications : BackgroundRow
    data object Battery : BackgroundRow
    data object Continue : BackgroundRow
}

/**
 * The Background step, after Shizuku: what keeps sync working while Tandem is out of sight. Notifications and the
 * battery exemption are each a row; A on one that isn't granted shows the system prompt, A on Continue moves on.
 * Skipped by the flow when both are already granted.
 */
@Composable
internal fun BackgroundStep(vm: SetupViewModel, status: TopStatus, later: (() -> Unit)?) {
    val granted by vm.permissions.state.collectAsState()
    val at by vm.backgroundAt.collectAsState()
    val rows = listOf(BackgroundRow.Notifications, BackgroundRow.Battery, BackgroundRow.Continue)
    fun choose(r: BackgroundRow) {
        when (r) {
            BackgroundRow.Notifications -> if (!granted.notifications) vm.requestPermission(PermissionRequest.Notifications)
            BackgroundRow.Battery -> if (!granted.battery) vm.requestPermission(PermissionRequest.Battery)
            BackgroundRow.Continue -> vm.backgroundDone()
        }
    }
    val cursor = rows.getOrNull(at.coerceIn(0, rows.lastIndex)) ?: BackgroundRow.Continue
    val label = when (cursor) {
        BackgroundRow.Notifications -> if (granted.notifications) null else "Allow"
        BackgroundRow.Battery -> if (granted.battery) null else "Allow"
        BackgroundRow.Continue -> "Continue"
    }
    SetupFrame(status, SetupStep.Background, listOfNotNull(label?.let { Hint(Action.A, it) { choose(cursor) } }), vm::back, later = later) {
        StepTitle("Keep Tandem running")
        StepSub("Sync runs in the background while you play. Two settings keep Android from getting in its way.")
        Options(
            rows, at, vm::pointBackground, ::choose,
            text = { r ->
                when (r) {
                    BackgroundRow.Notifications -> OptionText(NOTIFICATIONS_TITLE, NOTIFICATIONS_DETAIL, if (granted.notifications) "allowed" else null)
                    BackgroundRow.Battery -> OptionText(BATTERY_TITLE, BATTERY_DETAIL, if (granted.battery) "allowed" else null)
                    BackgroundRow.Continue -> OptionText(
                        "Continue",
                        if (granted.allGranted) "Both are set." else "You can allow these later in Settings › Sync.",
                    )
                }
            },
        )
        StepNote(BATTERY_NOTE)
    }
}
