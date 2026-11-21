package dev.tandem.app.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu

/** One label and value; [mono] for versions, sizes, hashes and paths. */
data class Fact(val label: String, val value: String, val mono: Boolean = false)

/** Label and value pairs: labels in ink3 down a [labelWidth] column, values in ink beside them, wrapping if long. */
@Composable
fun Facts(items: List<Fact>, modifier: Modifier = Modifier, labelWidth: Dp = 120.dp) {
    val c = LocalEmu.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in items) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(f.label, Modifier.width(labelWidth), style = EmuType.body.copy(color = c.ink3))
                BasicText(f.value, Modifier.weight(1f), style = (if (f.mono) EmuType.mono else EmuType.body).copy(color = c.ink))
            }
        }
    }
}
