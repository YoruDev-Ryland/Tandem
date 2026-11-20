package dev.emusync.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.theme.EmuColors
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

/**
 * Keeps a kit control out of keyboard focus: keys reach it only through the scaffold's routing, so Compose's own
 * focus (TAB, unhandled arrows) can never sit somewhere other than the cursor. Click semantics are unaffected.
 * Put it before the clickable.
 */
fun Modifier.noKeyFocus(): Modifier = focusProperties { canFocus = false }

/** The only corner radius in the app. */
val EmuRadius = RoundedCornerShape(3.dp)

/** What a piece of text in a row says about itself; the colour follows, and flips on the cursor row. */
enum class Tone { Ink, Ink2, Ink3, Wait, Fault }

/**
 * The colour of [tone], on the cursor row when [focused]. On the inverted fill the secondary inks mix 62 % of the
 * way from focusBg to focusInk, and the status inks take focusInk outright: the words still carry the status.
 */
fun EmuColors.tone(tone: Tone, focused: Boolean): Color = when {
    !focused -> when (tone) {
        Tone.Ink -> ink
        Tone.Ink2 -> ink2
        Tone.Ink3 -> ink3
        Tone.Wait -> wait
        Tone.Fault -> fault
    }
    tone == Tone.Ink2 || tone == Tone.Ink3 -> lerp(focusBg, focusInk, 0.62f)
    else -> focusInk
}

/** Whether the enclosing row is the cursor row, for [Cell]. */
val LocalRowFocused = staticCompositionLocalOf { false }

/**
 * One row cell: a single line that ends in an ellipsis, coloured by [tone] for the row it sits in. On the cursor row
 * a status word (wait, fault) loses its colour to the inversion, so it takes weight instead.
 */
@Composable
fun Cell(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Ink, style: TextStyle = EmuType.body) {
    val c = LocalEmu.current
    val focused = LocalRowFocused.current
    val status = focused && (tone == Tone.Wait || tone == Tone.Fault)
    BasicText(
        text,
        modifier,
        style = style.copy(color = c.tone(tone, focused), fontWeight = if (status) FontWeight.SemiBold else style.fontWeight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A 40 dp list row. The cursor row is drawn inverted with the 3 dp radius; every other row has a hairline on top,
 * except the one right under the cursor ([ruleAbove] false). There is no ripple: the inversion is the feedback.
 */
@Composable
fun CursorRow(
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ruleAbove: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalEmu.current
    val look = if (focused) {
        Modifier.background(c.focusBg, EmuRadius)
    } else if (ruleAbove) {
        Modifier.drawBehind { drawLine(c.rule, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1.dp.toPx()) }
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .then(look)
            .noKeyFocus()
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = focused }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalRowFocused provides focused) { content() }
    }
}
