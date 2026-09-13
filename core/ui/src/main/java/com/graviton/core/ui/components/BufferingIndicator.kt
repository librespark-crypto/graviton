package com.graviton.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.graviton.core.ui.R
import com.graviton.core.ui.theme.LocalGlassUi
import com.graviton.core.ui.theme.glassBorderColor
import com.graviton.core.ui.theme.glassContainerColor

/**
 * Graviton's network-buffering indicator.
 *
 * A Material 3 Expressive-inspired loader: a shape that continuously morphs between a rounded
 * square and a circle while rotating, wrapped in a slowly spinning orbital arc — the app's
 * gravitational identity as a loading state. The animation only runs while [visible] is true,
 * so an idle player never pays for it.
 *
 * Percentage correctness: this component never invents progress. [progress] must be a REAL
 * measurement from the playback pipeline (for Graviton that is the player's buffered percentage
 * divided by 100); pass null — or simply omit it — whenever no reliable value exists and the
 * indicator shows the label only, with no number.
 *
 * The indicator respects the Glass UI preference: frosted translucent container when enabled,
 * a standard Material 3 elevated surface otherwise.
 */
@Composable
fun BufferingIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier,
    /** Real 0..1 progress from the streaming pipeline, or null when it cannot be measured. */
    progress: Float? = null,
    label: String = stringResource(R.string.state_buffering),
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(180)) + scaleIn(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            initialScale = 0.85f,
        ),
        exit = fadeOut(animationSpec = tween(160)) + scaleOut(targetScale = 0.9f),
    ) {
        val glass = LocalGlassUi.current
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = if (glass) glassContainerColor() else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = if (glass) BorderStroke(1.dp, glassBorderColor()) else null,
            tonalElevation = if (glass) 0.dp else 2.dp,
            shadowElevation = if (glass) 8.dp else 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MorphingOrbitLoader(
                    modifier = Modifier.size(56.dp),
                    progress = progress,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = MaterialTheme.typography.labelLarge.letterSpacing * 1.4f,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress?.let { real ->
                    Text(
                        text = "${(real * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * The expressive shape-morphing loader itself: a rounded square that morphs to a circle and back
 * while rotating, with a gentle scale pulse, orbited by a sweeping elliptical arc.
 *
 * Implemented as a single [Canvas] driven by [rememberInfiniteTransition] — one cheap draw pass
 * per frame with only vector primitives, and no animation at all when it is not composed (the
 * player only composes it while the player is genuinely buffering).
 */
@Composable
fun MorphingOrbitLoader(
    modifier: Modifier = Modifier,
    /** When non-null, the orbital sweep reflects real progress instead of looping freely. */
    progress: Float? = null,
) {
    val transition = rememberInfiniteTransition(label = "BufferingLoader")
    val morph by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Morph",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
        ),
        label = "Rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Pulse",
    )
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
        ),
        label = "Orbit",
    )

    val coreStart = MaterialTheme.colorScheme.primary
    val coreEnd = MaterialTheme.colorScheme.tertiary
    val orbitColor = MaterialTheme.colorScheme.tertiary

    val semanticDescription = if (progress != null) {
        "${(progress * 100).toInt()}%"
    } else {
        ""
    }

    Canvas(
        modifier = modifier.semantics { contentDescription = semanticDescription },
    ) {
        // The gentle scale pulse comes straight from the infinite transition; no second animation.
        val core = size.minDimension * 0.42f * pulse
        // A square morphs to a circle as the corner radius grows from 0 to half the side.
        val cornerRadius = CornerRadius(core * 0.5f * morph, core * 0.5f * morph)
        val coreTopLeft = Offset(center.x - core / 2f, center.y - core / 2f)
        val coreBrush = Brush.linearGradient(
            colors = listOf(coreStart, coreEnd),
            start = coreTopLeft,
            end = Offset(coreTopLeft.x + core, coreTopLeft.y + core),
        )

        rotate(degrees = rotation, pivot = center) {
            drawRoundRect(
                brush = coreBrush,
                topLeft = coreTopLeft,
                size = Size(core, core),
                cornerRadius = cornerRadius,
            )
        }

        // Orbital arc: an ellipse around the core. With real progress it sweeps exactly the
        // measured amount starting from the top; otherwise it performs a continuous orbit.
        val orbitRx = size.minDimension * 0.5f * pulse
        val orbitRy = size.minDimension * 0.34f * pulse
        val sweep = progress?.let { 360f * it } ?: 300f
        val startAngle = if (progress != null) -90f else orbit % 360f
        drawArc(
            color = orbitColor.copy(alpha = 0.9f),
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(center.x - orbitRx, center.y - orbitRy),
            size = Size(orbitRx * 2f, orbitRy * 2f),
            style = Stroke(width = size.minDimension * 0.055f, cap = StrokeCap.Round),
        )
    }
}
