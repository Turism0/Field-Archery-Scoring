package uk.co.fnurble.nfas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF2E5E3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4E8CB),
    onPrimaryContainer = Color(0xFF0B2112),
    secondary = Color(0xFF7A5230),
    secondaryContainer = Color(0xFFF2DCC6),
    tertiary = Color(0xFF8A3B2E),
    tertiaryContainer = Color(0xFFFFDAD3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FC79B),
    onPrimary = Color(0xFF0B2112),
    primaryContainer = Color(0xFF1F4429),
    onPrimaryContainer = Color(0xFFC4E8CB),
    secondary = Color(0xFFD9B48F),
    secondaryContainer = Color(0xFF5A3C20),
    tertiary = Color(0xFFFFB4A6),
    tertiaryContainer = Color(0xFF6E2A1F),
)

@Composable
fun NfasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
