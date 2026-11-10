package app.espcontrol.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Accent = Color(0xFF7AA2FF)
    val Orange = Color(0xFFFF9B00)
    val Muted = Color(0xFF9AA0B2)
    val Text = Color(0xFFF2F3F7)
    val Green = Color(0xFF4ADE80)
    val RedCh = Color(0xFFFF3B30)
    val GreenCh = Color(0xFF34C759)
    val BlueCh = Color(0xFF3B6BFF)
    val Off = Color(0xFF4A4D5A)
    val Warn = Color(0xFFFFB454)
    val Danger = Color(0xFFFF8A80)
    val Glass = Color(0xB812141C)
    val Line = Color(0x24FFFFFF)
    val Card = Color(0xDB1E212D)
    val Track = Color(0x1AFFFFFF)
    val Bg1 = Color(0xFF161924)
    val Bg2 = Color(0xFF0B0C10)
}

@Composable
fun EspTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = C.Accent, background = C.Bg2, surface = C.Bg2),
        content = content,
    )
}
