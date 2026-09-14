package com.graviton.core.ui.theme
import androidx.compose.ui.graphics.isUnspecified

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Graviton's optional frosted-glass visual style.
 *
 * Glass UI is a preference-driven surface treatment on top of the standard Material 3 theme: the
 * same color scheme, typography and components, but with translucent containers, hairline borders,
 * soft blue/violet gradient depth behind content and rounded floating surfaces. It deliberately
 * never blurs video or other continuously animating content — blur is applied only to the static
 * decorative backdrop, where the render result is cached, and pure translucency is used everywhere
 * else so playback is never affected.
 */
val LocalGlassUi = staticCompositionLocalOf { false }

/** True when the user enabled the Glass UI style. */
@Composable
fun isGlassUiEnabled(): Boolean = LocalGlassUi.current

private fun Color.isDarkColor(): Boolean = luminance() < 0.5f

/**
 * Translucent container color used by glass surfaces. Derived from the active color scheme so the
 * app accent (including dynamic color) still drives the palette.
 */
@Composable
fun glassContainerColor(): Color {
    val dark = MaterialTheme.colorScheme.surface.isDarkColor()
    val base = if (dark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }
    return base.copy(alpha = if (dark) 0.72f else 0.80f)
}

/** Hairline border color for glass surfaces; brighter on dark backgrounds for separation. */
@Composable
fun glassBorderColor(): Color {
    val dark = MaterialTheme.colorScheme.surface.isDarkColor()
    val base = if (dark) Color.White else MaterialTheme.colorScheme.outline
    return base.copy(alpha = if (dark) 0.14f else 0.22f)
}

/**
 * The container color every full screen `Scaffold` should use. It is transparent when Glass UI is
 * enabled so the shared [GlassBackground] backdrop shows through the translucent bars and cards;
 * otherwise it is the standard opaque surface container.
 */
@Composable
fun gravitonScreenContainerColor(): Color =
    if (LocalGlassUi.current) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer

/**
 * A frosted card: translucent container, hairline border, generous corner radius and a soft
 * gradient tint that keeps Graviton's blue/violet identity visible through the glass.
 * When Glass UI is off this renders as a plain Material 3 surface with the same shape, so callers
 * do not need to branch on the preference themselves.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    color: Color = Color.Unspecified,
    border: Boolean = LocalGlassUi.current,
    contentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    if (LocalGlassUi.current) {
        val container = if (color.isUnspecified) glassContainerColor() else color
        val scheme = MaterialTheme.colorScheme
        val tintTop = scheme.primary.copy(alpha = 0.10f)
        val tintBottom = scheme.tertiary.copy(alpha = 0.10f)
        val borderColor = glassBorderColor()
        Surface(
            modifier = modifier,
            shape = shape,
            color = Color.Transparent,
            contentColor = if (contentColor.isUnspecified) Color.Unspecified else contentColor,
            border = if (border) BorderStroke(1.dp, borderColor) else null,
        ) {
            Box(
                modifier = Modifier
                    .background(container, shape)
                    .drawBehind {
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(tintTop, tintBottom),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height),
                            ),
                        )
                    },
            ) {
                content()
            }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = if (color.isUnspecified) MaterialTheme.colorScheme.surfaceContainer else color,
            contentColor = if (contentColor.isUnspecified) Color.Unspecified else contentColor,
            border = if (border) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) else null,
            content = content,
        )
    }
}

/**
 * The static blue/violet gradient backdrop drawn behind app content when Glass UI is enabled.
 *
 * The blobs are soft radial gradients; on Android 12+ they are additionally diffused with
 * [Modifier.blur], which is cheap here because the layer never changes after the first frame.
 * Below Android 12 the blur modifier is a no-op and the radial gradients alone provide the soft
 * frosted look — the required translucent fallback.
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.isDarkColor()
    val baseColor = if (dark) scheme.surface else scheme.surfaceContainerLowest
    val blobAlpha = if (dark) 0.30f else 0.18f

    val blobPrimary = scheme.primary.copy(alpha = blobAlpha)
    val blobTertiary = scheme.tertiary.copy(alpha = blobAlpha)
    val blobSecondary = scheme.secondary.copy(alpha = blobAlpha * 0.7f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    // Static layers only: RenderEffect caches the blurred result, so this costs
                    // nothing after the first frame and is skipped entirely below API 31.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.blur(64.dp)
                    } else {
                        Modifier
                    },
                )
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    // Primary blob, upper start quadrant.
                    drawCircle(
                        color = blobPrimary,
                        radius = w * 0.55f,
                        center = Offset(w * 0.15f, h * 0.12f),
                    )
                    // Tertiary blob, lower end quadrant.
                    drawCircle(
                        color = blobTertiary,
                        radius = w * 0.50f,
                        center = Offset(w * 0.92f, h * 0.85f),
                    )
                    // Secondary blob, middle top, softer.
                    drawCircle(
                        color = blobSecondary,
                        radius = w * 0.40f,
                        center = Offset(w * 0.55f, h * -0.05f),
                    )
                },
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            content()
        }
    }
}
