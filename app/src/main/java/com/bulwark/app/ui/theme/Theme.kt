package com.bulwark.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

/**
 * Bulwark's semantic colours for the current theme.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf`: the value only
 * changes when the whole theme does, so there is nothing to gain from tracking
 * reads individually.
 */
private val LocalBulwarkColors = staticCompositionLocalOf { LightBulwarkColors }

/**
 * Bulwark's own palette, on top of Material's.
 *
 * `MaterialTheme.bulwark.caution` rather than an imported `CautionText`. The
 * import was the bug: a single light-theme value used on whichever surface
 * happened to be underneath.
 */
val MaterialTheme.bulwark: BulwarkColors
    @Composable @ReadOnlyComposable get() = LocalBulwarkColors.current

/**
 * ## Dynamic colour is off, deliberately
 *
 * It was on by default - the project template's setting, never chosen - so on
 * Android 12+ every Material role came from the user's wallpaper while
 * Bulwark's semantic colours stayed fixed. Two colour systems that knew
 * nothing about each other.
 *
 * `android.md` recommends Material You "where it fits", and here it does not.
 * In this app colour *is* meaning: caution, refusal, and four community
 * ratings. A scheme derived from a photograph cannot promise that the surface
 * behind a warning keeps it readable, and cannot promise that "recommended"
 * and "unsafe" stay distinguishable from each other. Wallpaper harmony is a
 * fair trade in a gallery app; it is not one here.
 *
 * The cost is honest: Bulwark looks the same on every phone. That is the
 * intended outcome for a tool whose colours are load-bearing.
 */
@Composable
fun BulwarkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val bulwarkColors = if (darkTheme) DarkBulwarkColors else LightBulwarkColors

    CompositionLocalProvider(LocalBulwarkColors provides bulwarkColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
