package dev.tandem.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tandem.app.ui.theme.EmuFonts
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu

/** The fixed width of a system mark, so SWITCH, 3DS and GBA line up like labels on a cartridge shelf. */
val MARK_W = 44.dp

/**
 * A game's system mark: its system's short name in cartridge-label caps inside the button outline (1.5 dp rule2, the
 * 3 dp radius). An unknown system is a dash in ink3 with no outline: never the emulator's name. On the cursor row the
 * outline and text follow the inversion. It reads as "System: GBA".
 */
@Composable
fun SystemMark(label: String?, modifier: Modifier = Modifier) {
    val c = LocalEmu.current
    val focused = LocalRowFocused.current
    val box = modifier.width(MARK_W).height(18.dp)
    if (label == null) {
        Box(box.clearAndSetSemantics { contentDescription = "System unknown" }, contentAlignment = Alignment.Center) {
            BasicText("—", style = EmuType.mark.copy(color = c.tone(Tone.Ink3, focused), textAlign = TextAlign.Center))
        }
        return
    }
    Box(
        box.border(1.5.dp, if (focused) c.tone(Tone.Ink3, true) else c.rule2, EmuRadius)
            .clearAndSetSemantics { contentDescription = "System: $label" },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label.uppercase(), style = EmuType.mark.copy(color = c.tone(Tone.Ink2, focused), textAlign = TextAlign.Center), maxLines = 1)
    }
}

/** How one device holds a game, as its presence mark draws it (the web's device presence matrix). */
enum class Presence { Newest, Older, Coming, Waiting, Empty, Absent, Conflict }

/** The width of one device column of presence marks. */
val PRESENCE_W = 52.dp

/**
 * One device's cell in a game row: a drawn 12 dp square, single 1.5 dp stroke. Filled has the newest save, outlined an
 * older one, half filled the newest is on its way, amber outlined with "wait" it arrives when the emulator closes,
 * dashed installed with no save yet; a dash is not installed and a red "×2" two different saves. The shape carries the
 * meaning, so colour is never the only signal; [description] is what it reads as.
 */
@Composable
fun PresenceMark(p: Presence, description: String, modifier: Modifier = Modifier) {
    val c = LocalEmu.current
    val focused = LocalRowFocused.current
    val tone = when (p) {
        Presence.Newest, Presence.Coming -> Tone.Ink
        Presence.Older -> Tone.Ink2
        Presence.Empty, Presence.Absent -> Tone.Ink3
        Presence.Waiting -> Tone.Wait
        Presence.Conflict -> Tone.Fault
    }
    val ink = c.tone(tone, focused)
    val word = EmuType.meta.copy(fontFamily = EmuFonts.Barlow, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = ink,
        fontFeatureSettings = "tnum")
    Row(
        modifier.width(PRESENCE_W).height(20.dp).clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (p) {
            Presence.Absent -> BasicText("—", style = word.copy(fontWeight = FontWeight.Normal, fontSize = 14.sp))
            Presence.Conflict -> BasicText("×2", style = word)
            else -> {
                Canvas(Modifier.size(12.dp)) {
                    val sw = 1.5.dp.toPx()
                    val inset = sw / 2
                    val r = CornerRadius(1.dp.toPx())
                    val side = size.width - sw
                    val dash = if (p == Presence.Empty) PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 2.dp.toPx())) else null
                    if (p == Presence.Newest) drawRoundRect(ink, Offset(inset, inset), Size(side, side), r)
                    if (p == Presence.Coming) drawRect(ink, Offset(inset, size.height / 2), Size(side, size.height / 2 - inset))
                    drawRoundRect(ink, Offset(inset, inset), Size(side, side), r, style = Stroke(sw, pathEffect = dash))
                }
                if (p == Presence.Waiting) BasicText("wait", style = word)
            }
        }
    }
}
