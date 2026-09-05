package com.graviton.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.graviton.core.model.AppTheme
import com.graviton.core.model.ApplicationPreferences
import com.graviton.core.model.ThemeConfig

/**
 * The single entry point every Graviton screen themes itself with.
 *
 * Video and music players used to call [GravitonTheme] directly with hardcoded arguments
 * (`darkTheme = true`, default accent), which is why changing the app accent left the player
 * untouched. Routing every surface through this one function means the accent, dark mode and
 * contrast preference always come from the same place, and because [preferences] arrives as
 * observed state the change is applied on the next recomposition — no restart.
 *
 * @param forceDarkTheme for immersive surfaces such as the video player, which are always dark
 *   regardless of the app's light/dark setting. The *accent* still follows the preference; only
 *   the light/dark axis is pinned.
 */
@Composable
fun GravitonAppTheme(
    preferences: ApplicationPreferences,
    forceDarkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = forceDarkTheme || when (preferences.themeConfig) {
        ThemeConfig.SYSTEM -> isSystemInDarkTheme()
        ThemeConfig.OFF -> false
        ThemeConfig.ON -> true
    }
    GravitonTheme(
        darkTheme = darkTheme,
        highContrastDarkTheme = preferences.useHighContrastDarkTheme,
        dynamicColor = preferences.useDynamicColors,
        appTheme = preferences.appTheme,
        content = content,
    )
}

/** Fallback used before preferences have loaded, so the first frame is not un-themed. */
@Composable
fun GravitonAppTheme(
    preferences: ApplicationPreferences?,
    forceDarkTheme: Boolean = false,
    fallbackAppTheme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit,
) {
    if (preferences != null) {
        GravitonAppTheme(preferences = preferences, forceDarkTheme = forceDarkTheme, content = content)
    } else {
        GravitonTheme(darkTheme = forceDarkTheme || isSystemInDarkTheme(), appTheme = fallbackAppTheme, content = content)
    }
}
