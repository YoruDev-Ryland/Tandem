package dev.emusync.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.kit.BumperTabs
import dev.emusync.app.ui.kit.Cell
import dev.emusync.app.ui.kit.ConsoleScaffold
import dev.emusync.app.ui.kit.CursorList
import dev.emusync.app.ui.kit.EmptyLine
import dev.emusync.app.ui.kit.ErrorLine
import dev.emusync.app.ui.kit.Hint
import dev.emusync.app.ui.kit.Tone
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

/** What the tab asks its host to do. */
class ActivityActions(val loadOlder: () -> Unit = {}, val refresh: () -> Unit = {}, val back: () -> Unit = {})

private val SUBS = listOf("This device", "Log")
private const val SUB_LOG = 1
private val TIME_W = 120.dp

private sealed interface ActivityRow {
    data class Item(val entry: ActivityEntry) : ActivityRow
    data object Older : ActivityRow
}

/** A log line with a key that survives new lines arriving above it: its text and how many equal lines came before. */
private data class LogLine(val key: String, val text: String)

private fun logLines(lines: List<String>): List<LogLine> {
    val seen = HashMap<String, Int>()
    return lines.map { l -> LogLine("$l#${seen.merge(l, 1, Int::plus)}", l) }.asReversed()   // newest first
}

/**
 * The Activity tab. "This device" lists what the server recorded for this device, newest first, ending in a "Load
 * older" row while there is more; "Log" lists this app's own diagnostic log, newest first, in mono. L2/R2 switch.
 */
@Composable
fun ActivityScreen(ui: ActivityUi, log: List<String>, top: @Composable RowScope.() -> Unit, actions: ActivityActions) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    var rowAt by rememberSaveable { mutableIntStateOf(0) }
    var logAt by rememberSaveable { mutableIntStateOf(0) }

    fun loadOlder() { if (ui.hasOlder && !ui.loadingOlder) actions.loadOlder() }

    val hints = if (sub == SUB_LOG) {
        listOf(Hint(Action.B, "Back", actions.back))
    } else {
        buildList {
            if (ui.hasOlder) add(Hint(Action.A, "Load older", ::loadOlder))
            add(Hint(Action.X, "Refresh", actions.refresh))
            add(Hint(Action.B, "Back", actions.back))
        }
    }

    ConsoleScaffold(top = top, tabs = { BumperTabs(SUBS, sub, { sub = it }, level = 2) }, hints = hints) {
        if (sub == SUB_LOG) LogPane(log, logAt) { logAt = it } else DevicePane(ui, rowAt, { rowAt = it }, ::loadOlder)
    }
}

@Composable
private fun DevicePane(ui: ActivityUi, selected: Int, onSelect: (Int) -> Unit, loadOlder: () -> Unit) {
    val c = LocalEmu.current
    val rows = remember(ui.entries, ui.hasOlder) {
        ui.entries.map<ActivityEntry, ActivityRow> { ActivityRow.Item(it) } + if (ui.hasOlder) listOf(ActivityRow.Older) else emptyList()
    }
    Column(Modifier.fillMaxSize()) {
        ui.offlineNote?.let {
            BasicText(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = EmuType.meta.copy(color = c.wait))
        }
        when {
            !ui.signedIn -> EmptyLine("Sign in to see this device's activity.")
            rows.isNotEmpty() -> {
                Row(
                    Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    val h = EmuType.header.copy(color = c.ink3)
                    BasicText("TIME", Modifier.width(TIME_W), style = h)
                    BasicText("WHAT HAPPENED", Modifier.weight(1f), style = h)
                }
                CursorList(
                    rows,
                    key = { if (it is ActivityRow.Item) it.entry.id else "older" },
                    selected = selected, onSelect = onSelect,
                    onOpen = { if (it is ActivityRow.Older) loadOlder() },
                    modifier = Modifier.weight(1f, fill = false),
                ) { r, _ ->
                    when (r) {
                        is ActivityRow.Item -> {
                            Cell(r.entry.time, Modifier.width(TIME_W), tone = Tone.Ink3, style = EmuType.mono)
                            Cell(r.entry.message, Modifier.weight(1f), tone = if (r.entry.fault) Tone.Fault else Tone.Ink, style = EmuType.rowPrimary)
                        }
                        ActivityRow.Older -> {
                            Cell("", Modifier.width(TIME_W))
                            Cell(if (ui.loadingOlder) "Loading…" else "Load older", Modifier.weight(1f),
                                tone = if (ui.loadingOlder) Tone.Ink3 else Tone.Ink, style = EmuType.rowPrimary)
                        }
                    }
                }
                if (ui.olderError) {
                    BasicText(
                        "Couldn't load older activity. Try again.",
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = EmuType.body.copy(color = c.fault),
                    )
                } else if (!ui.hasOlder) {
                    BasicText("Nothing older", Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = EmuType.body.copy(color = c.ink3))
                }
            }
            ui.error != null -> ErrorLine(ui.error, Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
            ui.loaded -> EmptyLine("No activity from this device yet.")
            else -> EmptyLine("Loading…")
        }
    }
}

@Composable
private fun LogPane(log: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val lines = remember(log) { logLines(log) }
    if (lines.isEmpty()) {
        EmptyLine("Nothing logged yet.")
        return
    }
    CursorList(lines, key = { it.key }, selected = selected, onSelect = onSelect, onOpen = {}) { l, _ ->
        Cell(l.text, Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.mono)
    }
}
