package dev.tandem.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The clock and battery for the top bar's right edge, as a console's system bar shows them. */
@Immutable
data class Readout(val time: String, val battery: Int?, val charging: Boolean = false)

/** Provided by the activity (it ticks); absent in previews and tests, where the top bar shows no clock. */
val LocalReadout = compositionLocalOf<Readout?> { null }

private val H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val H12 = DateTimeFormatter.ofPattern("h:mm", Locale.US)

/** "14:05", or "2:05" when this device uses a 12-hour clock. */
fun readoutTime(now: Instant, zone: ZoneId, is24Hour: Boolean): String = (if (is24Hour) H24 else H12).format(now.atZone(zone))

/** What the readout says to a screen reader. */
fun readoutDescription(r: Readout): String = buildString {
    append(r.time)
    if (r.battery != null) {
        append(", battery ${r.battery} percent")
        if (r.charging) append(", charging")
    }
}

/**
 * The time, then a drawn battery and its level, in mono ink3: system data, quiet. The battery is an outline with a
 * fill to its level (single 1.5 dp stroke, the presence marks' weight).
 */
@Composable
fun SystemReadout(r: Readout) {
    val c = LocalEmu.current
    val text = EmuType.mono.copy(color = c.ink3)
    Row(
        Modifier.clearAndSetSemantics { contentDescription = readoutDescription(r) },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.battery != null) {
            val level = r.battery.coerceIn(0, 100) / 100f
            Canvas(Modifier.size(width = 20.dp, height = 10.dp)) {
                val sw = 1.5.dp.toPx()
                val tip = 2.dp.toPx()
                val body = Size(size.width - tip - sw, size.height - sw)
                drawRoundRect(c.ink3, Offset(sw / 2, sw / 2), body, CornerRadius(1.5.dp.toPx()), style = Stroke(sw))
                drawRect(c.ink3, Offset(size.width - tip, size.height * 0.3f), Size(tip, size.height * 0.4f))
                val pad = sw + 1.dp.toPx()
                val inner = (body.width - 1.dp.toPx() - pad).coerceAtLeast(0f)
                if (level > 0f) drawRect(c.ink3, Offset(pad, pad), Size(inner * level, size.height - 2 * pad))
            }
            BasicText("${r.battery}%", style = text, maxLines = 1)
        }
        BasicText(r.time, Modifier.padding(start = 6.dp), style = text, maxLines = 1)
    }
}
