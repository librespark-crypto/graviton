package com.graviton.feature.player.buttons

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.graviton.core.common.extensions.isTelevision
import com.graviton.core.ui.components.tvFocusRing
import com.graviton.core.ui.glass.GlassTokens
import com.graviton.core.ui.glass.glassAwareColor
import com.graviton.core.ui.glass.isGlassUiEnabled
import com.graviton.core.ui.glass.rememberGlassSpec
import com.graviton.feature.player.LocalUseMaterialYouControls
import com.graviton.feature.player.state.LocalHoldSpeedController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerButton(
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val viewConfiguration = LocalViewConfiguration.current
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val isTv = remember { context.isTelevision }

    val holdSpeedController = LocalHoldSpeedController.current

    // A press that outlives the long-press timeout is never a click. Depending on the button it
    // either runs its own long-press action or starts the player-wide temporary speed boost; in
    // both cases the release must not fall through to onClick, which is what used to make holding
    // the speed button open the speed menu.
    LaunchedEffect(interactionSource, onClick, onLongClick, holdSpeedController) {
        var suppressClick = false
        var didHoldSpeed = false
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    suppressClick = false
                    didHoldSpeed = false
                    delay(viewConfiguration.longPressTimeoutMillis)
                    val longClick = onLongClick
                    if (longClick != null) {
                        suppressClick = true
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        longClick()
                    } else if (holdSpeedController.startHold()) {
                        suppressClick = true
                        didHoldSpeed = true
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }

                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    if (didHoldSpeed) holdSpeedController.endHold()
                    if (!suppressClick) onClick()
                    suppressClick = false
                    didHoldSpeed = false
                }
            }
        }
    }

    val glassEnabled = isGlassUiEnabled()
    val spec = rememberGlassSpec()
    // Glass UI: transport controls sit in frosted round wells so they stay prominent and readable
    // over bright video. An explicit containerColor (e.g. the unlock button's dark well) always
    // wins; otherwise the fill crossfades between transparent and glass.
    val glassContainer = glassAwareColor(glass = spec.controlContainer, normal = containerColor)
    val resolvedContainer = if (containerColor == Color.Transparent) glassContainer else containerColor
    val glassBorder = glassAwareColor(glass = spec.border, normal = Color.Transparent)
    val glassModifier = if (glassEnabled) {
        Modifier.clip(CircleShape).border(GlassTokens.BorderWidth, glassBorder, CircleShape)
    } else {
        Modifier
    }

    if (LocalUseMaterialYouControls.current) {
        FilledTonalIconButton(
            onClick = {},
            enabled = isEnabled,
            modifier = modifier.size(40.dp).tvFocusRing(isTv).then(glassModifier),
            interactionSource = interactionSource,
            colors = if (glassEnabled) {
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = spec.controlContainer,
                )
            } else {
                IconButtonDefaults.filledTonalIconButtonColors()
            },
            content = content,
        )
    } else {
        // The classic (non-Material-You) controls still need to read against arbitrary video, but
        // the colour comes from the theme rather than a hardcoded white so the accent selected in
        // settings reaches the player. PlayerActivity themes itself dark, so onSurface is a
        // near-white that is already tinted by the active scheme.
        val controlColor = MaterialTheme.colorScheme.onSurface
        CompositionLocalProvider(
            LocalContentColor provides controlColor,
            LocalRippleConfiguration provides RippleConfiguration(
                color = controlColor,
                rippleAlpha = RippleAlpha(
                    pressedAlpha = 0.5f,
                    focusedAlpha = 0.5f,
                    draggedAlpha = 0.5f,
                    hoveredAlpha = 0.5f
                )
            )
        ) {
            IconButton(
                onClick = {},
                enabled = isEnabled,
                modifier = modifier.tvFocusRing(isTv).then(glassModifier),
                interactionSource = interactionSource,
                colors = IconButtonDefaults.iconButtonColors().copy(containerColor = resolvedContainer),
                content = content,
            )
        }
    }
}
