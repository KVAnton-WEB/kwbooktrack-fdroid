package ru.antonkw.kwbooktrack.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * AMOLED-тема: фон 100% чёрный, акцент #D5F1CA.
 * Светлая схема убрана намеренно — приложение всегда тёмное.
 */
private val AmoledColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentContainer,
    onPrimaryContainer = OnAccentContainer,
    secondary = AccentSecondary,
    onSecondary = OnAccentSecondary,
    secondaryContainer = AccentContainer,
    onSecondaryContainer = OnAccentContainer,
    tertiary = Accent,
    onTertiary = OnAccent,
    tertiaryContainer = AccentContainer,
    onTertiaryContainer = OnAccentContainer,
    background = PureBlack,
    onBackground = OnDark,
    surface = PureBlack,
    onSurface = OnDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    surfaceTint = Accent,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = OnDark,
    inversePrimary = Accent,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
)

@Composable
fun KwBooktrackTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AmoledColorScheme,
        typography = Typography,
        content = content,
    )
}
