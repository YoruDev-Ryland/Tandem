package dev.emusync.app.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.ui.state.ServerConn
import dev.emusync.app.ui.state.TopStatus
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

/** The server half of the top bar's status: what it says and its tone. */
fun serverCopy(status: TopStatus, zone: ZoneId = ZoneId.systemDefault()): Pair<String, Tone> = when (status.server) {
    ServerConn.Connected -> "Server connected" to Tone.Ink2
    ServerConn.Offline -> (status.lastSync?.let { "Offline · last updated ${HHMM.format(it.atZone(zone))}" } ?: "Offline") to Tone.Wait
    ServerConn.SignedOut -> "Signed out" to Tone.Fault
}

/**
 * The console top bar, for ConsoleScaffold's top slot: the wordmark, the page tabs (L1/R1), then this device's name,
 * the server connection and Shizuku. While Shizuku is not ready the Shizuku status is a button running
 * [onShizukuFix]; with no fix to offer (Shizuku not installed) it only says it is off.
 */
@Composable
fun RowScope.TopBar(
    tabs: List<String>,
    active: Int,
    onTab: (Int) -> Unit,
    status: TopStatus,
    onShizukuFix: (() -> Unit)?,
    badge: (Int) -> String? = { null },
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val c = LocalEmu.current
    val meta = EmuType.meta.copy(fontWeight = FontWeight.Medium)
    // The wordmark is the product name as plain text in Barlow Semi Condensed 700 (spec §6).
    BasicText("EmuSync", style = EmuType.section.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink))
    BumperTabs(tabs, active, onTab, badge = badge)
    Row(
        Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val name = status.deviceName
        if (name != null) {
            BasicText(
                name, Modifier.weight(1f, fill = false), style = meta.copy(fontWeight = FontWeight.SemiBold, color = c.ink),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val (server, tone) = serverCopy(status, zone)
        BasicText(server, style = meta.copy(color = c.tone(tone, false)), maxLines = 1)
        if (status.shizuku == ShizukuState.Ready) {
            BasicText("Shizuku on", style = meta.copy(color = c.ink2), maxLines = 1)
        } else if (onShizukuFix == null) {
            BasicText("Shizuku is off", style = meta.copy(color = c.fault), maxLines = 1)
        } else {
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .noKeyFocus()
                    .clickable(interactionSource = null, indication = PressIndication, role = Role.Button, onClick = onShizukuFix),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Shizuku is off: tap to fix", style = meta.copy(color = c.fault), maxLines = 1)
            }
        }
    }
}
