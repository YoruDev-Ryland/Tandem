package dev.emusync.app.ui.kit

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

/** What an empty list says instead of rows, inset to line up with row text. */
@Composable
fun EmptyLine(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = EmuType.body.copy(color = LocalEmu.current.ink2))
}
