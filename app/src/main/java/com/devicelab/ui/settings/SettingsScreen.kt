package com.devicelab.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.BuildConfig
import com.devicelab.R
import com.devicelab.data.export.ExportFormat
import com.devicelab.data.settings.Settings
import com.devicelab.data.settings.ThemeMode
import com.devicelab.ui.components.ChoiceRow
import com.devicelab.ui.components.GlassCard
import com.devicelab.ui.components.HairlineDivider
import com.devicelab.ui.components.Overline
import com.devicelab.ui.components.ScreenScaffold
import com.devicelab.ui.components.ScreenTitle
import com.devicelab.ui.components.SelectChip
import com.devicelab.ui.components.ToggleRow
import com.devicelab.ui.components.contentWidth
import com.devicelab.ui.theme.Spacing

/**
 * Settings.
 *
 * Every switch here changes how the app presents what it read. None of them changes what the
 * platform will return, and none unlocks additional data -- there is no "advanced mode" that
 * reads more, because no permission, root or setting would make Android answer a question it
 * does not answer. The two reporting switches say so in their own notes: turning provenance
 * off hides the evidence, not the values, and hiding unavailable rows makes the device look
 * more capable than it reported.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings

    ScreenScaffold(modifier = modifier) {
        item(key = "title") {
            ScreenTitle(
                title = stringResource(R.string.settings_title),
                caption = stringResource(R.string.settings_caption),
                modifier = Modifier.contentWidth(),
            )
        }

        item(key = "appearance") {
            SettingsGroup(
                title = stringResource(R.string.settings_appearance),
                modifier = Modifier.contentWidth(),
            ) {
                Overline(stringResource(R.string.settings_theme))
                Spacer(Modifier.height(Spacing.xs))
                ThemeMode.entries.forEach { mode ->
                    ChoiceRow(
                        title = mode.label,
                        selected = settings.theme == mode,
                        onClick = { viewModel.setTheme(mode) },
                    )
                }
                HairlineDivider(alpha = 0.35f)
                ToggleRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    note = stringResource(R.string.settings_dynamic_color_note),
                    checked = settings.dynamicColor && state.dynamicColorAvailable,
                    onCheckedChange = viewModel::setDynamicColor,
                    enabled = state.dynamicColorAvailable,
                )
                HairlineDivider(alpha = 0.35f)
                ToggleRow(
                    title = stringResource(R.string.settings_reduce_motion),
                    note = stringResource(R.string.settings_reduce_motion_note),
                    checked = settings.reduceMotion,
                    onCheckedChange = viewModel::setReduceMotion,
                )
                HairlineDivider(alpha = 0.35f)
                ToggleRow(
                    title = stringResource(R.string.settings_monospace),
                    note = stringResource(R.string.settings_monospace_note),
                    checked = settings.monospaceValues,
                    onCheckedChange = viewModel::setMonospaceValues,
                )
            }
        }

        item(key = "reporting") {
            SettingsGroup(
                title = stringResource(R.string.settings_reporting),
                modifier = Modifier.contentWidth(),
            ) {
                ToggleRow(
                    title = stringResource(R.string.settings_show_provenance),
                    note = stringResource(R.string.settings_show_provenance_note),
                    checked = settings.showProvenance,
                    onCheckedChange = viewModel::setShowProvenance,
                )
                HairlineDivider(alpha = 0.35f)
                ToggleRow(
                    title = stringResource(R.string.settings_show_unavailable),
                    note = stringResource(R.string.settings_show_unavailable_note),
                    checked = settings.showUnavailable,
                    onCheckedChange = viewModel::setShowUnavailable,
                )
            }
        }

        item(key = "data") {
            SettingsGroup(
                title = stringResource(R.string.settings_data),
                modifier = Modifier.contentWidth(),
            ) {
                Overline(stringResource(R.string.settings_keep_snapshots))
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.settings_keep_snapshots_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                KeepChoices(
                    selected = settings.keepSnapshots,
                    onSelect = viewModel::setKeepSnapshots,
                )
                Spacer(Modifier.height(Spacing.lg))
                HairlineDivider(alpha = 0.35f)
                Spacer(Modifier.height(Spacing.md))
                Overline(stringResource(R.string.settings_export_format))
                Spacer(Modifier.height(Spacing.xs))
                ExportFormat.entries.forEach { format ->
                    ChoiceRow(
                        title = format.label,
                        selected = settings.exportFormat == format,
                        onClick = { viewModel.setExportFormat(format) },
                        note = ".${format.extension} · ${format.mimeType}",
                    )
                }
            }
        }

        item(key = "about") {
            SettingsGroup(
                title = stringResource(R.string.settings_about),
                modifier = Modifier.contentWidth(),
            ) {
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.lg))
                Overline(stringResource(R.string.settings_privacy_title))
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.settings_privacy_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A titled card of related settings. */
@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Overline(title, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.sm))
        GlassCard(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * How many snapshots to keep.
 *
 * Chips rather than radio rows: five numeric options read as a scale side by side, where five
 * stacked radios read as five unrelated choices. The trim is applied on the next save rather
 * than immediately, so lowering the limit does not delete anything until the user asks for a
 * new snapshot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeepChoices(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Settings.KEEP_CHOICES.forEach { count ->
            SelectChip(
                text = count.toString(),
                selected = selected == count,
                onClick = { onSelect(count) },
            )
        }
    }
}
