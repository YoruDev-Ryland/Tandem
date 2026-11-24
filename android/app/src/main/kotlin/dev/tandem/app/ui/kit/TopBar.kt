package dev.tandem.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
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
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.ui.state.ServerConn
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu

/**
 * The product's mark, "two marks": a filled square (has the newest) next to an outlined one (has a copy), drawn in
 * [LocalEmu] ink so it follows the theme. [size] is the height of the squares; the mark is 1.9 times as wide. The
 * geometry is the icon's 48-unit grid (filled 5,14 18x20; outlined 26.5,15.5 15x17 with a 3-unit stroke).
 */
@Composable
fun TandemMark(size: Dp, modifier: Modifier = Modifier) {
    val ink = LocalEmu.current.ink
    Canvas(modifier.size(width = size * (38f / 20f), height = size).clearAndSetSemantics {}) {
        val u = this.size.height / 20f   // one grid unit; the mark spans x 5..43 and y 14..34
        drawRect(ink, Offset(0f, 0f), Size(18 * u, 20 * u))
        drawRect(ink, Offset(21.5f * u, 1.5f * u), Size(15 * u, 17 * u), style = Stroke(3 * u))
    }
}

/** The wordmark: the mark at the word's cap height, then TANDEM in Barlow Semi Condensed 700. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val c = LocalEmu.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TandemMark(11.dp)
        BasicText("TANDEM", style = EmuType.section.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em, color = c.ink))
    }
}

/**
 * The server half of the top bar's status, only when something is wrong: null while connected (nothing is labelled
 * when all is well). How long ago this device last synced is the hint bar's status line, not this.
 */
fun serverCopy(status: TopStatus): Pair<String, Tone>? = when (status.server) {
    ServerConn.Connected -> null
    ServerConn.Offline -> "Server unreachable" to Tone.Wait
    ServerConn.SignedOut -> "Signed out" to Tone.Fault
    ServerConn.Revoked -> "Removed from server" to Tone.Fault
    ServerConn.Moving -> "Server is moving files" to Tone.Wait
}

/**
 * The console top bar, for ConsoleScaffold's top slot: the wordmark, the page tabs (L1/R1), then this device's name and
 * only what is wrong (the server unreachable, Shizuku off), then the clock and battery as a console's system bar shows
 * them ([SystemReadout], from [LocalReadout]). While Shizuku is not ready its status is a button running
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
) {
    val c = LocalEmu.current
    val meta = EmuType.meta.copy(fontWeight = FontWeight.Medium)
    Wordmark()
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
        serverCopy(status)?.let { (server, tone) -> BasicText(server, style = meta.copy(color = c.tone(tone, false)), maxLines = 1) }
        if (status.shizuku == ShizukuState.Ready) {
            // Nothing to say.
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
        LocalReadout.current?.let { SystemReadout(it) }
    }
}
