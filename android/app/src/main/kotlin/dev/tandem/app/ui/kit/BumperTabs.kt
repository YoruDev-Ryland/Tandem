package dev.tandem.app.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.tandem.app.ui.feedback.Cue
import dev.tandem.app.ui.feedback.LocalCueSink
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.input.OnAction
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu

/**
 * Tabs switched with the shoulder buttons, shown between their glyphs: level 1 (page tabs) uses L1/R1, level 2
 * (sub-tabs) L2/R2. Switching stops at either end. A tab with a [badge] reads "{label} {badge}" in fault until it is
 * the active tab, as "Conflicts 1" does. Tapping a tab switches to it.
 */
@Composable
fun BumperTabs(items: List<String>, active: Int, onChange: (Int) -> Unit, level: Int = 1, badge: (Int) -> String? = { null }) {
    require(level == 1 || level == 2) { "level is 1 (L1/R1) or 2 (L2/R2)" }
    val c = LocalEmu.current
    val prev = if (level == 1) Action.L1 else Action.L2
    val next = if (level == 1) Action.R1 else Action.R2
    val cues = LocalCueSink.current

    OnAction(enabled = items.isNotEmpty()) { a ->
        if (a != prev && a != next) return@OnAction false
        val to = (active + if (a == next) 1 else -1).coerceIn(0, items.lastIndex)
        if (to != active) onChange(to) else cues?.cue(Cue.Reject)   // no tab further this way
        true
    }

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Glyph(prev)
        items.forEachIndexed { i, label ->
            val b = badge(i)
            val on = i == active
            val color = when {
                on -> c.ink
                b != null -> c.fault
                else -> c.ink3
            }
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .noKeyFocus()
                    .clickable(interactionSource = null, indication = null, role = Role.Tab) { if (!on) onChange(i) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(if (b != null) "$label $b" else label, style = EmuType.tab.copy(color = color), maxLines = 1)
            }
        }
        Glyph(next)
    }
}
