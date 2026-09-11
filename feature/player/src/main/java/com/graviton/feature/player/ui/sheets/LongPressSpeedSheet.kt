package com.graviton.feature.player.ui.sheets

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
import com.graviton.core.ui.R
import com.graviton.feature.player.state.HoldSpeedGesture
import com.graviton.feature.player.ui.ExpressiveRadioRow
import com.graviton.feature.player.ui.OverlayView

/**
 * Picks the speed used by the temporary hold-to-boost gesture.
 *
 * This is deliberately a different sheet from the playback-speed selector. Choosing a value here
 * configures the gesture; it does not change the speed the video is playing at right now.
 */
@Composable
fun BoxScope.LongPressSpeedSheet(
    show: Boolean,
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayView(
        modifier = modifier,
        show = show,
        title = stringResource(R.string.long_press_speed),
        onDismiss = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.long_press_speed_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .padding(horizontal = 12.dp)
                .selectableGroup(),
        ) {
            HoldSpeedGesture.SPEED_PRESETS.filter { it > 1f }.forEach { speed ->
                ExpressiveRadioRow(
                    selected = kotlin.math.abs(speed - currentSpeed) < 0.01f,
                    text = "${HoldSpeedGesture.formatOverlaySpeed(speed)}×",
                    onClick = {
                        onSpeedSelected(speed)
                        onDismiss()
                    },
                )
            }
        }
    }
}
