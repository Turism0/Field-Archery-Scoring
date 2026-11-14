package uk.co.fieldarcheryscoring.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/**
 * Set to false to always use the green palette below instead of the phone's
 * wallpaper colours (Material You, Android 12+).
 */
private const val USE_DYNAMIC_COLOUR = true

// Forest green palette, generated from seed #2E5E3A (Material Theme Builder style tones).
private val Light = lightColorScheme(
    primary = Color(0xFF3A6A45),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBBF0C3),
    onPrimaryContainer = Color(0xFF00210B),
    secondary = Color(0xFF516351),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4E8D1),
    onSecondaryContainer = Color(0xFF0F1F11),
    tertiary = Color(0xFF7A5230),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF2D1600),
    background = Color(0xFFF6FBF3),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF3),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF717970),
    outlineVariant = Color(0xFFC1C9BE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEAEFE7),
    surfaceContainerHigh = Color(0xFFE5EAE2),
    surfaceContainerHighest = Color(0xFFDFE4DC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA0D4A8),
    onPrimary = Color(0xFF033919),
    primaryContainer = Color(0xFF21512E),
    onPrimaryContainer = Color(0xFFBBF0C3),
    secondary = Color(0xFFB8CCB6),
    onSecondary = Color(0xFF243425),
    secondaryContainer = Color(0xFF3A4B3A),
    onSecondaryContainer = Color(0xFFD4E8D1),
    tertiary = Color(0xFFEDBD92),
    onTertiary = Color(0xFF472A08),
    tertiaryContainer = Color(0xFF603F1C),
    onTertiaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFF101510),
    onBackground = Color(0xFFDFE4DC),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFDFE4DC),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BE),
    outline = Color(0xFF8B9389),
    outlineVariant = Color(0xFF414941),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF262B26),
    surfaceContainerHighest = Color(0xFF313631),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FieldArcheryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        USE_DYNAMIC_COLOUR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Dark
        else -> Light
    }
    // 2.9.03 (Pete: screens should scale to the phone): everything (text, spacing, tiles, buttons) is laid out for a
    // typical phone, about 390 × 800 dp, and scaled to fit this one – smaller phones scale down, bigger ones up, within
    // 0.85–1.2. The phone's own text size setting still applies on top. Tablets and big landscape windows aren't scaled.
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val factor = remember(config.screenWidthDp, config.screenHeightDp, density.density) {
        screenScale(config.screenWidthDp, config.screenHeightDp)
    }
    CompositionLocalProvider(LocalDensity provides Density(density.density * factor, density.fontScale)) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
    }
}

/** How much to scale the layout for a screen of [widthDp] × [heightDp] (see [FieldArcheryTheme]). */
internal fun screenScale(widthDp: Int, heightDp: Int): Float {
    if (widthDp <= 0 || heightDp <= 0) return 1f
    val short = minOf(widthDp, heightDp).toFloat()
    val long = maxOf(widthDp, heightDp).toFloat()
    if (short >= 600f) return 1f   // a tablet: lay out as it is
    return minOf(short / 390f, long / 800f).coerceIn(0.85f, 1.2f)
}
