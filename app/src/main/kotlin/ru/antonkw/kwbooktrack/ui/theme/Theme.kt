package ru.antonkw.kwbooktrack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * AMOLED-тема: фон 100% чёрный, акцент #D5F1CA.
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

/** Светлая схема: тот же акцент, но на светлом фоне. */
private val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    primaryContainer = LightAccentContainer,
    onPrimaryContainer = LightOnAccentContainer,
    secondary = AccentSecondaryLight,
    onSecondary = OnAccentSecondaryLight,
    secondaryContainer = LightAccentContainer,
    onSecondaryContainer = LightOnAccentContainer,
    tertiary = LightAccent,
    onTertiary = LightOnAccent,
    tertiaryContainer = LightAccentContainer,
    onTertiaryContainer = LightOnAccentContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightBackground,
    onSurface = LightOnBackground,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    surfaceTint = LightAccent,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = OnInverseSurfaceLight,
    inversePrimary = InversePrimaryLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

/**
 * Тема приложения: AMOLED-чёрная или светлая.
 *
 * [darkTheme] вычисляется снаружи (в `MainActivity`) из настройки
 * «Как в системе / Светлая / Тёмная».
 */
@Composable
fun KwBooktrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AmoledColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
