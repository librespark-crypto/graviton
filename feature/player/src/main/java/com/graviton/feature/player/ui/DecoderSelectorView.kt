package com.graviton.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.graviton.core.model.DecoderMode
import com.graviton.core.ui.R
import com.graviton.feature.player.extensions.descriptionRes
import com.graviton.feature.player.extensions.nameRes

/**
 * Decoder picker, presented on the translucent player sheet.
 *
 * Labels are Auto / HW / HW+ / SW and each one maps to a genuinely different renderer
 * configuration — see `DecoderModeConfiguration`.
 */
@Composable
fun BoxScope.DecoderSelectorView(
    modifier: Modifier = Modifier,
    show: Boolean,
    currentDecoderMode: DecoderMode,
    onDecoderModeSelected: (DecoderMode) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayView(
        modifier = modifier,
        show = show,
        title = stringResource(R.string.decoder),
        onDismiss = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.decoder_mode_change_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp, top = 8.dp)
                .padding(horizontal = 12.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DecoderMode.entries.forEach { mode ->
                ExpressiveRadioRow(
                    selected = mode == currentDecoderMode,
                    text = stringResource(mode.nameRes()),
                    supportingText = stringResource(mode.descriptionRes()),
                    onClick = {
                        onDecoderModeSelected(mode)
                        onDismiss()
                    },
                )
            }
        }
    }
}
