package com.graviton.core.ui.glass

import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Crossfades a color between its normal and glass values when the Glass UI setting changes, so
 * toggling the mode animates in place instead of popping. When glass is off the target is exactly
 * [normal], which keeps the stock UI pixel-identical.
 */
@Composable
fun glassAwareColor(glass: Color, normal: Color): Color {
    val target = if (isGlassUiEnabled()) glass else normal
    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = GlassTokens.CrossfadeDurationMillis),
        label = "glassAwareColor",
    ).value
}

/**
 * The single reusable glass container.
 *
 * Frosted fill + 1dp edge highlight + faint top sheen + soft shadow, with large rounded corners.
 * Deliberately *not* a backdrop blur: Compose cannot blur what is behind a composable without
 * snapshotting, and doing that over video would cost per-frame GPU work. The translucency +
 * highlight is the efficient path, and dialogs get real system-composited blur via
 * [GlassDialogBackdropBlur] where the platform supports it.
 *
 * @param enabled when false the surface draws nothing (transparent, no border, no elevation),
 *   so wrapping an existing layout is visually a no-op and the OFF mode stays exact.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassTokens.PanelCornerRadius),
    glassColor: Color = rememberGlassSpec().panelContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shadowElevation: Dp = 12.dp,
    enabled: Boolean = isGlassUiEnabled(),
    content: @Composable () -> Unit,
) {
    val spec = rememberGlassSpec()
    val container by animateColorAsState(
        targetValue = if (enabled) glassColor else Color.Transparent,
        animationSpec = tween(durationMillis = GlassTokens.CrossfadeDurationMillis),
        label = "glassContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = if (enabled) spec.border else Color.Transparent,
        animationSpec = tween(durationMillis = GlassTokens.CrossfadeDurationMillis),
        label = "glassBorder",
    )
    val sheenColor by animateColorAsState(
        targetValue = if (enabled) spec.sheenTop else Color.Transparent,
        animationSpec = tween(durationMillis = GlassTokens.CrossfadeDurationMillis),
        label = "glassSheen",
    )
    Surface(
        modifier = modifier,
        shape = shape,
        color = container,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = if (enabled) shadowElevation else 0.dp,
        border = BorderStroke(GlassTokens.BorderWidth, borderColor),
    ) {
        Box {
            content()
            // Static top sheen: one cheap gradient, drawn once per composition, never animated.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(sheenColor, Color.Transparent),
                            endY = 320f,
                        ),
                    ),
            )
        }
    }
}

/**
 * Compact glass pill for transient playback overlays (seek feedback, content-scale indicator).
 * Prefer the capsule token over [GlassSurface] defaults so overlays stay consistent.
 */
@Composable
fun GlassCapsule(
    modifier: Modifier = Modifier,
    enabled: Boolean = isGlassUiEnabled(),
    content: @Composable () -> Unit,
) {
    val spec = rememberGlassSpec()
    GlassSurface(
        modifier = modifier,
        shape = CircleShape,
        glassColor = spec.capsuleContainer,
        shadowElevation = 8.dp,
        enabled = enabled,
        content = content,
    )
}

/**
 * Round glass well for icon buttons (player transport controls, unlock button).
 *
 * The container crossfades between transparent (glass off) and the glass control token; the
 * border only exists in glass mode.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val spec = rememberGlassSpec()
    val glassEnabled = isGlassUiEnabled()
    val container by animateColorAsState(
        targetValue = if (glassEnabled) spec.controlContainer else Color.Transparent,
        animationSpec = tween(durationMillis = GlassTokens.CrossfadeDurationMillis),
        label = "glassIconButtonContainer",
    )
    val borderColor by animateColorAsState(
        targetValue = if (glassEnabled) spec.border else Color.Transparent,
        animationSpec = tween(durationMillis = GlassTokens.CrossfadeDurationMillis),
        label = "glassIconButtonBorder",
    )
    IconButton(
        onClick = onClick,
        // Border is drawn inside the bounds: layout size and touch targets never change.
        modifier = modifier
            .clip(CircleShape)
            .border(GlassTokens.BorderWidth, borderColor, CircleShape),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = container),
        content = content,
    )
}

/**
 * Applies real, system-composited background blur behind a dialog window on API 31+.
 *
 * This is the only place Glass UI uses an actual blur, and it costs the app nothing per frame:
 * the window manager blurs once per backdrop change. Below API 31 — or when glass is off — this
 * is a no-op and the dialog's translucent fill is the fallback. Call from inside dialog content.
 */
@Composable
fun GlassDialogBackdropBlur(enabled: Boolean = isGlassUiEnabled()) {
    if (!enabled) return
    // Lint-checked API gate: the blur calls below are API 31+.
    if (!supportsGlassBackdropBlur()) return
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        try {
            window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window?.attributes?.let { attrs ->
                attrs.blurBehindRadius = GlassTokens.DialogBlurRadiusPx
                window.attributes = attrs
            }
        } catch (_: Exception) {
            // Window tweaks must never crash a dialog; the translucent fill stands alone.
        }
        onDispose {
            try {
                window?.attributes?.let { attrs ->
                    attrs.blurBehindRadius = 0
                    window.attributes = attrs
                }
                window?.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            } catch (_: Exception) {
                // Best-effort restore only.
            }
        }
    }
}
