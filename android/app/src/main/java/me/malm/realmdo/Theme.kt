package me.malm.realmdo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The mark's palette instead of the wallpaper's: lavender on warm cream,
 * deep violet ink, and clay for what is due. Dark mode keeps the lavender as
 * the accent on a violet-black ground.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF5E49C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FB),
    onPrimaryContainer = Color(0xFF2A1E6B),
    secondary = Color(0xFF5E49C9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E0FB),
    onSecondaryContainer = Color(0xFF2A1E6B),
    tertiary = Color(0xFFB8461F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE3D8),
    onTertiaryContainer = Color(0xFF4A1A08),
    error = Color(0xFFB3261E),
    background = Color(0xFFFBF7F0),
    onBackground = Color(0xFF1D1830),
    surface = Color(0xFFFBF7F0),
    onSurface = Color(0xFF1D1830),
    surfaceVariant = Color(0xFFF3EDE3),
    onSurfaceVariant = Color(0xFF625B75),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F3EB),
    surfaceContainer = Color(0xFFF3EDE3),
    surfaceContainerHigh = Color(0xFFEEE7DB),
    surfaceContainerHighest = Color(0xFFE8E0D2),
    outline = Color(0xFFCFC5B5),
    outlineVariant = Color(0xFFE7DFD2),
    inverseSurface = Color(0xFF1D1830),
    inverseOnSurface = Color(0xFFFBF7F0),
    inversePrimary = Color(0xFFB4A3F2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4A3F2),
    onPrimary = Color(0xFF221A45),
    primaryContainer = Color(0xFF3A3060),
    onPrimaryContainer = Color(0xFFE7E0FB),
    secondary = Color(0xFFB4A3F2),
    onSecondary = Color(0xFF221A45),
    secondaryContainer = Color(0xFF3A3060),
    onSecondaryContainer = Color(0xFFE7E0FB),
    tertiary = Color(0xFFF29270),
    onTertiary = Color(0xFF3A1406),
    tertiaryContainer = Color(0xFF4A2518),
    onTertiaryContainer = Color(0xFFFBE3D8),
    error = Color(0xFFF2B8B5),
    background = Color(0xFF15121C),
    onBackground = Color(0xFFEEE9F7),
    surface = Color(0xFF15121C),
    onSurface = Color(0xFFEEE9F7),
    surfaceVariant = Color(0xFF282235),
    onSurfaceVariant = Color(0xFFA9A1BC),
    surfaceContainerLowest = Color(0xFF110E17),
    surfaceContainerLow = Color(0xFF1B1724),
    surfaceContainer = Color(0xFF221D2D),
    surfaceContainerHigh = Color(0xFF2A2436),
    surfaceContainerHighest = Color(0xFF332C42),
    outline = Color(0xFF4A4259),
    outlineVariant = Color(0xFF332C42),
    inverseSurface = Color(0xFFEEE9F7),
    inverseOnSurface = Color(0xFF15121C),
    inversePrimary = Color(0xFF5E49C9),
)

/** What Material's scheme has no slot for. */
@Immutable
data class Extra(
    /** Grouped rows and cards: white on cream, lifted violet on black. */
    val card: Color,
)

val LocalExtra = staticCompositionLocalOf { Extra(card = Color.White) }

@Composable
fun RealmdoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) Dark else Light
    CompositionLocalProvider(LocalExtra provides Extra(card = if (dark) Color(0xFF1F1A29) else Color.White)) {
        MaterialTheme(colorScheme = scheme) {
            // Material's own default is black: any surface in a colour
            // outside the scheme (the card) would inherit it.
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
        }
    }
}
