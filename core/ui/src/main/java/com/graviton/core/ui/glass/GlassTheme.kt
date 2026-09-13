package com.graviton.core.ui.glass

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether the optional Glass UI visual mode is enabled.
 *
 * Provided by [com.graviton.core.ui.theme.GravitonAppTheme] from
 * `ApplicationPreferences.glassUiEnabled`, so every screen — including the video player, which
 * themes itself through the same entry point — reacts to the setting on the next recomposition
 * without a restart. Defaults to `false`, which keeps the stock Material 3 experience.
 */
val LocalGlassEnabled = compositionLocalOf { false }

/** Reads [LocalGlassEnabled] without pulling the composition local into every call site. */
@Composable
@ReadOnlyComposable
fun isGlassUiEnabled(): Boolean = LocalGlassEnabled.current

/**
 * True platform backdrop blur is only used where the OS composites it for free: dialog windows
 * on API 31+ via `Window.setBackgroundBlurRadius`. Everywhere else — and always during video
 * playback — glass surfaces gracefully fall back to GPU-cheap translucent fills, so no per-frame
 * blur work is ever added to the playback path.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun supportsGlassBackdropBlur(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Shared glass geometry and animation constants. */
object GlassTokens {
    /** Crossfade duration when toggling Glass UI on/off. Short on purpose: no layout churn. */
    const val CrossfadeDurationMillis: Int = 180

    /** Background blur radius applied to dialog windows on API 31+. System-composited. */
    const val DialogBlurRadiusPx: Int = 64

    val PanelCornerRadius: Dp = 28.dp
    val CardCornerRadius: Dp = 24.dp
    val BorderWidth: Dp = 1.dp
}

/**
 * Theme-resolved glass colors.
 *
 * All roles derive from the active Material 3 [androidx.compose.material3.ColorScheme], so the
 * user's accent (including Graviton's blue/violet schemes and dynamic color) tints the glass
 * automatically. Light/dark is resolved from the surface luminance rather than the system theme,
 * because immersive surfaces such as the video player are pinned dark.
 */
@Immutable
data class GlassSpec(
    val isDark: Boolean,
    /** Large floating panels: player top/bottom control bars. */
    val panelContainer: Color,
    /** Bottom/side sheets over video. Slightly more opaque than panels to keep rows readable. */
    val sheetContainer: Color,
    /** Compact capsules: volume/brightness, seek feedback, speed indicator. */
    val capsuleContainer: Color,
    /** Round icon-button wells: player transport controls. */
    val controlContainer: Color,
    /** Settings/preference cards and list rows. */
    val cardContainer: Color,
    /** Bottom navigation bar and navigation rail. */
    val navigationContainer: Color,
    /** Dialogs. Paired with real window blur on API 31+, translucent fallback below. */
    val dialogContainer: Color,
    /** Very subtle white edge highlight (dark) / outline edge (light). */
    val border: Color,
    /** Faint top sheen drawn over glass fills. */
    val sheenTop: Color,
)

@Composable
fun rememberGlassSpec(): GlassSpec {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.surface.luminance() < 0.5f
    return remember(scheme, isDark) {
        if (isDark) {
            GlassSpec(
                isDark = true,
                panelContainer = scheme.surfaceContainerHigh.copy(alpha = 0.62f),
                sheetContainer = scheme.surfaceContainerHigh.copy(alpha = 0.74f),
                capsuleContainer = Color.Black.copy(alpha = 0.55f),
                controlContainer = Color.White.copy(alpha = 0.12f),
                cardContainer = scheme.surfaceContainerHighest.copy(alpha = 0.55f),
                navigationContainer = scheme.surfaceContainer.copy(alpha = 0.62f),
                dialogContainer = scheme.surfaceContainerHigh.copy(alpha = 0.80f),
                border = Color.White.copy(alpha = 0.16f),
                sheenTop = Color.White.copy(alpha = 0.07f),
            )
        } else {
            GlassSpec(
                isDark = false,
                // Light glass stays noticeably more opaque: translucent fills over a bright
                // background lose contrast fast, and text must keep its hierarchy.
                panelContainer = scheme.surfaceContainerHigh.copy(alpha = 0.80f),
                sheetContainer = scheme.surfaceContainerHigh.copy(alpha = 0.86f),
                capsuleContainer = scheme.surfaceContainerHighest.copy(alpha = 0.82f),
                controlContainer = scheme.surfaceContainerHighest.copy(alpha = 0.70f),
                cardContainer = scheme.surfaceContainerHighest.copy(alpha = 0.65f),
                navigationContainer = scheme.surfaceContainer.copy(alpha = 0.78f),
                dialogContainer = scheme.surfaceContainerHigh.copy(alpha = 0.88f),
                border = scheme.outline.copy(alpha = 0.28f),
                sheenTop = Color.White.copy(alpha = 0.22f),
            )
        }
    }
}
