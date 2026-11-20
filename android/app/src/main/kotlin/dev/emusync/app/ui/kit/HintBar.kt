package dev.emusync.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.theme.EmuFonts
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

/** One entry in the hint bar: the button, what it does here, and the same thing for a tap. */
data class Hint(val action: Action, val label: String, val onClick: () -> Unit)

private val Action.label: String
    get() = when (this) {
        Action.Up -> "Up"
        Action.Down -> "Down"
        Action.Left -> "Left"
        Action.Right -> "Right"
        else -> name
    }

private val Action.isFace: Boolean get() = this == Action.A || this == Action.B || this == Action.X || this == Action.Y

/**
 * A controller button as drawn in the UI: a face button (A B X Y) is a disc, like the button itself; a shoulder or
 * D-pad direction is an outlined key. [inverted] is for a glyph on an inverted fill (a primary button). It is
 * decoration: it carries no semantics, the text next to it does.
 */
@Composable
fun Glyph(action: Action, modifier: Modifier = Modifier, inverted: Boolean = false) {
    val c = LocalEmu.current
    val style = EmuType.meta.copy(fontFamily = EmuFonts.Barlow, fontWeight = FontWeight.Bold)
    if (action.isFace) {
        Box(
            modifier.size(20.dp).background(if (inverted) c.bg else c.glyph, CircleShape).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            BasicText(action.label, style = style.copy(fontSize = 11.sp, color = c.ink))
        }
    } else {
        Box(
            modifier.height(18.dp).border(1.5.dp, c.ink3, EmuRadius).padding(horizontal = 5.dp).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            BasicText(action.label, style = style.copy(fontSize = 10.sp, color = c.ink2))
        }
    }
}

/**
 * The 44 dp bar along the bottom: [status] on the left, then what each button does on this screen. Every hint is
 * also a button with a 48 dp target that reads as its label, so a touch user can do everything a pad user can.
 */
@Composable
fun HintBar(status: String?, hints: List<Hint>) {
    val c = LocalEmu.current
    val text = EmuType.meta.copy(fontWeight = FontWeight.Medium)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .drawBehind { drawLine(c.rule, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1.dp.toPx()) }
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (status != null) {
                BasicText(status, style = text.copy(color = c.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        for (h in hints) {
            Row(
                Modifier
                    .requiredHeight(48.dp)
                    .widthIn(min = 48.dp)
                    .clickable(interactionSource = null, indication = PressIndication, role = Role.Button, onClick = h.onClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            ) {
                Glyph(h.action)
                BasicText(h.label, style = text.copy(color = c.ink2), maxLines = 1)
            }
        }
    }
}
