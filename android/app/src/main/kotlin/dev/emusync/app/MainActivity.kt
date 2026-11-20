package dev.emusync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.theme.EmuTheme
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

/** The launcher. The console scaffold and screens arrive in later tasks; for now it shows the wordmark. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EmuTheme { EmuSyncRoot() } }
    }
}

@Composable
fun EmuSyncRoot() {
    val c = LocalEmu.current
    Box(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 28.dp), contentAlignment = Alignment.CenterStart) {
            // The wordmark is the product name as plain text in Barlow Semi Condensed 700 (spec §6).
            BasicText("EmuSync", style = EmuType.title.copy(fontWeight = FontWeight.Bold, color = c.ink))
        }
    }
}
