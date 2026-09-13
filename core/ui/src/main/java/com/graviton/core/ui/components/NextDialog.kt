package com.graviton.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.graviton.core.ui.glass.GlassDialogBackdropBlur
import com.graviton.core.ui.glass.GlassTokens
import com.graviton.core.ui.glass.glassAwareColor
import com.graviton.core.ui.glass.isGlassUiEnabled
import com.graviton.core.ui.glass.rememberGlassSpec

@Composable
fun NextDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    dialogProperties: DialogProperties = NextDialogDefaults.dialogProperties,
) {
    val configuration = LocalConfiguration.current
    val spec = rememberGlassSpec()
    // System-composited backdrop blur on API 31+; a no-op elsewhere (translucency is the fallback).
    GlassDialogBackdropBlur()
    val containerColor = glassAwareColor(
        glass = spec.dialogContainer,
        normal = AlertDialogDefaults.containerColor,
    )

    AlertDialog(
        title = title,
        text = { Column { content() } },
        modifier = modifier
            .widthIn(max = configuration.screenWidthDp.dp - NextDialogDefaults.dialogMargin * 2),
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        properties = dialogProperties,
        shape = if (isGlassUiEnabled()) RoundedCornerShape(GlassTokens.PanelCornerRadius) else AlertDialogDefaults.shape,
        containerColor = containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    )
}

@Composable
fun NextDialogWithDoneAndCancelButtons(
    title: String,
    onDoneClick: () -> Unit,
    onDismissClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    NextDialog(
        title = { Text(text = title) },
        confirmButton = { DoneButton(onClick = onDoneClick) },
        dismissButton = { CancelButton(onClick = onDismissClick) },
        onDismissRequest = onDismissClick,
        content = content,
    )
}

object NextDialogDefaults {
    val dialogProperties: DialogProperties = DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        decorFitsSystemWindows = true,
    )
    val dialogMargin: Dp = 16.dp
}
