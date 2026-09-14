package com.graviton.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.graviton.core.common.extensions.isTelevision
import com.graviton.core.ui.R
import com.graviton.core.ui.components.ClickablePreferenceItem
import com.graviton.core.ui.components.ListSectionTitle
import com.graviton.core.ui.components.NextTopAppBar
import com.graviton.core.ui.components.requestFocusUntilLanded
import com.graviton.core.ui.components.thenIf
import com.graviton.core.ui.designsystem.NextIcons
import com.graviton.core.ui.theme.gravitonScreenContainerColor
import com.graviton.settings.utils.tvFocusDown

/**
 * The root Settings screen.
 *
 * Categories are grouped by intent (appearance, playback, library, general) so related settings
 * sit together and the page reads as four clear sections instead of one long identical list. The
 * navigation architecture is unchanged: each row still maps to a [Setting] handled by
 * [onItemClick].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    onItemClick: (Setting) -> Unit,
) {
    val context = LocalContext.current
    val isTv = remember { context.isTelevision }
    val settingGroups = remember { SettingGroup.entries }

    // Remember which row was focused so returning from a sub-screen restores focus to it instead
    // of jumping back to the first item. Survives navigation because it is saveable.
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    val itemFocusRequester = remember { FocusRequester() }

    if (isTv) {
        LaunchedEffect(Unit) {
            itemFocusRequester.requestFocusUntilLanded()
        }
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.settings),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.tvFocusDown(itemFocusRequester),
                    ) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = gravitonScreenContainerColor(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .thenIf(isTv) { focusGroup() }
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            var index = 0
            settingGroups.forEach { group ->
                ListSectionTitle(text = stringResource(id = group.titleResId))
                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    group.rows.forEachIndexed { rowIndex, row ->
                        val absoluteIndex = index
                        index++
                        ClickablePreferenceItem(
                            modifier = Modifier.thenIf(isTv) {
                                thenIf(absoluteIndex == focusedIndex) { focusRequester(itemFocusRequester) }
                                    .onFocusChanged { if (it.isFocused) focusedIndex = absoluteIndex }
                            },
                            title = stringResource(id = row.titleResId),
                            description = stringResource(id = row.descriptionResId),
                            icon = row.icon,
                            onClick = { onItemClick(row.setting) },
                            isFirstItem = rowIndex == 0,
                            isLastItem = rowIndex == group.rows.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

enum class Setting {
    APPEARANCE,
    MEDIA_LIBRARY,
    PLAYER,
    GESTURES,
    DECODER,
    AUDIO,
    SUBTITLE,
    MUSIC,
    GENERAL,
    ABOUT,
}

/** A titled group of related settings, rendered as one visual section. */
private enum class SettingGroup(
    @StringRes val titleResId: Int,
    val rows: List<SettingRow>,
) {
    APPEARANCE(
        titleResId = R.string.settings_group_appearance,
        rows = listOf(SettingRow.APPEARANCE),
    ),
    PLAYBACK(
        titleResId = R.string.settings_group_playback,
        rows = listOf(
            SettingRow.PLAYER,
            SettingRow.GESTURES,
            SettingRow.DECODER,
            SettingRow.AUDIO,
            SettingRow.SUBTITLE,
        ),
    ),
    LIBRARY_AND_MUSIC(
        titleResId = R.string.settings_group_library,
        rows = listOf(
            SettingRow.MEDIA_LIBRARY,
            SettingRow.MUSIC,
        ),
    ),
    GENERAL(
        titleResId = R.string.settings_group_general,
        rows = listOf(
            SettingRow.GENERAL,
            SettingRow.ABOUT,
        ),
    ),
}

private enum class SettingRow(
    val titleResId: Int,
    val descriptionResId: Int,
    val icon: ImageVector,
    val setting: Setting,
) {
    APPEARANCE(
        titleResId = R.string.appearance_name,
        descriptionResId = R.string.appearance_description,
        icon = NextIcons.Appearance,
        setting = Setting.APPEARANCE,
    ),
    MEDIA_LIBRARY(
        titleResId = R.string.media_library,
        descriptionResId = R.string.media_library_description,
        icon = NextIcons.Movie,
        setting = Setting.MEDIA_LIBRARY,
    ),
    PLAYER(
        titleResId = R.string.player_name,
        descriptionResId = R.string.player_description,
        icon = NextIcons.Player,
        setting = Setting.PLAYER,
    ),
    GESTURES(
        titleResId = R.string.gestures_name,
        descriptionResId = R.string.gestures_description,
        icon = NextIcons.SwipeHorizontal,
        setting = Setting.GESTURES,
    ),
    DECODER(
        titleResId = R.string.decoder,
        descriptionResId = R.string.decoder_desc,
        icon = NextIcons.Decoder,
        setting = Setting.DECODER,
    ),
    AUDIO(
        titleResId = R.string.audio,
        descriptionResId = R.string.audio_desc,
        icon = NextIcons.Audio,
        setting = Setting.AUDIO,
    ),
    SUBTITLE(
        titleResId = R.string.subtitle,
        descriptionResId = R.string.subtitle_desc,
        icon = NextIcons.Subtitle,
        setting = Setting.SUBTITLE,
    ),
    MUSIC(
        titleResId = R.string.music_settings,
        descriptionResId = R.string.music_settings_description,
        icon = NextIcons.Audio,
        setting = Setting.MUSIC,
    ),
    GENERAL(
        titleResId = R.string.general_name,
        descriptionResId = R.string.general_description,
        icon = NextIcons.ExtraSettings,
        setting = Setting.GENERAL,
    ),
    ABOUT(
        titleResId = R.string.about_name,
        descriptionResId = R.string.about_description,
        icon = NextIcons.Info,
        setting = Setting.ABOUT,
    ),
}
