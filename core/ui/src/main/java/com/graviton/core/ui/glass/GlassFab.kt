package com.graviton.core.ui.glass

import androidx.compose.foundation.border
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * Glass-aware FAB container.
 *
 * FABs are primary actions, so in glass mode they stay nearly opaque — just frosted enough to
 * read as glass while keeping full prominence and text/icon contrast.
 */
@Composable
fun glassFabContainerColor(): Color {
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val alpha = if (rememberGlassSpec().isDark) 0.85f else 0.92f
    return glassAwareColor(glass = primaryContainer.copy(alpha = alpha), normal = primaryContainer)
}

/** [FloatingActionButton] with the glass container and a hairline edge highlight in glass mode. */
@Composable
fun GlassFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.shape,
    content: @Composable () -> Unit,
) {
    val borderColor = glassAwareColor(glass = rememberGlassSpec().border, normal = Color.Transparent)
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.border(GlassTokens.BorderWidth, borderColor, shape),
        shape = shape,
        containerColor = glassFabContainerColor(),
        content = content,
    )
}

/** [ExtendedFloatingActionButton] with the glass container and edge highlight in glass mode. */
@Composable
fun GlassExtendedFloatingActionButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.extendedFabShape,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val borderColor = glassAwareColor(glass = rememberGlassSpec().border, normal = Color.Transparent)
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = icon,
        text = text,
        modifier = modifier.border(GlassTokens.BorderWidth, borderColor, shape),
        shape = shape,
        containerColor = glassFabContainerColor(),
        contentColor = contentColor,
    )
}
