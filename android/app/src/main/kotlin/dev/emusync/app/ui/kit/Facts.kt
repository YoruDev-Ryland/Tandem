package dev.emusync.app.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

/** One label and value; [mono] for versions, sizes, hashes and paths. */
data class Fact(val label: String, val value: String, val mono: Boolean = false)

/** Label and value pairs: labels in ink3 down a 120 dp column, values in ink beside them, wrapping if long. */
@Composable
fun Facts(items: List<Fact>, modifier: Modifier = Modifier) {
    val c = LocalEmu.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in items) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(f.label, Modifier.width(120.dp), style = EmuType.body.copy(color = c.ink3))
                BasicText(f.value, Modifier.weight(1f), style = (if (f.mono) EmuType.mono else EmuType.body).copy(color = c.ink))
            }
        }
    }
}
