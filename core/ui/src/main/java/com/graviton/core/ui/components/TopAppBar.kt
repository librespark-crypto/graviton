package com.graviton.core.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.graviton.core.ui.theme.LocalGlassUi
import com.graviton.core.ui.theme.glassBorderColor
import com.graviton.core.ui.theme.glassContainerColor

/**
 * Default app bar colors for Graviton screens. In Glass UI mode the bar becomes translucent so
 * the backdrop shows through; otherwise it is the standard opaque surface container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun defaultAppBarColors(): TopAppBarColors {
    return if (LocalGlassUi.current) {
        val container = glassContainerColor()
        TopAppBarDefaults.topAppBarColors(
            containerColor = container,
            scrolledContainerColor = container.copy(alpha = (container.alpha + 0.12f).coerceAtMost(1f)),
        )
    } else {
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextTopAppBar(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors? = null,
) {
    val glass = LocalGlassUi.current
    val borderColor = if (glass) glassBorderColor() else Color.Transparent
    TopAppBar(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = colors ?: defaultAppBarColors(),
        modifier = if (glass) {
            modifier.drawBehind {
                // Hairline separation so the translucent bar stays readable over bright content.
                val y = size.height - 1f
                drawLine(
                    color = borderColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
        } else {
            modifier
        },
        contentPadding = PaddingValues(horizontal = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextTopAppBar(
    modifier: Modifier = Modifier,
    title: String,
    fontWeight: FontWeight? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors? = null,
) {
    NextTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = fontWeight,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = colors,
        modifier = modifier,
    )
}
