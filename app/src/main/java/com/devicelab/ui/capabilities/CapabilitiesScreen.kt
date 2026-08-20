package com.devicelab.ui.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.R
import com.devicelab.core.model.Availability
import com.devicelab.core.model.Lab
import com.devicelab.core.model.MatrixRow
import com.devicelab.core.model.Support
import com.devicelab.ui.components.EmptyState
import com.devicelab.ui.components.FoldIndicator
import com.devicelab.ui.components.GlassCard
import com.devicelab.ui.components.HairlineDivider
import com.devicelab.ui.components.Overline
import com.devicelab.ui.components.ProgressBar
import com.devicelab.ui.components.ScreenScaffold
import com.devicelab.ui.components.ScreenTitle
import com.devicelab.ui.components.SearchField
import com.devicelab.ui.components.SelectChip
import com.devicelab.ui.components.StatusGlyph
import com.devicelab.ui.components.SupportLegend
import com.devicelab.ui.components.clickableRow
import com.devicelab.ui.components.contentWidth
import com.devicelab.ui.theme.LocalMonospaceValues
import com.devicelab.ui.theme.MonoValue
import com.devicelab.ui.theme.ProportionalValue
import com.devicelab.ui.theme.Spacing
import com.devicelab.ui.theme.colorFor

/**
 * The capability matrix: Section 18's Capability | Status | Details grid.
 *
 * The single most important thing this screen does is refuse to conflate the two kinds of
 * "no". A row that reads "Not on this Android version — requires API 31, this device is
 * running API 28" is a statement about the OS; "Not on this hardware — queried, the device
 * does not report it" is a statement about the device. They are separately filterable
 * precisely so that a reader can ask each question on its own.
 */
@Composable
fun CapabilitiesScreen(
    modifier: Modifier = Modifier,
    viewModel: CapabilitiesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ScreenScaffold(modifier = modifier) {
        item(key = "title") {
            ScreenTitle(
                title = stringResource(R.string.capabilities_title),
                caption = stringResource(R.string.capabilities_caption),
                modifier = Modifier.contentWidth(),
            )
        }

        item(key = "search") {
            Column(modifier = Modifier.contentWidth()) {
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                )
                Spacer(Modifier.height(Spacing.md))
                FilterChips(
                    state = state,
                    onFilterChange = viewModel::onFilterChange,
                )
            }
        }

        when {
            state.loading -> item(key = "loading") {
                GlassCard(modifier = Modifier.contentWidth()) {
                    Text(
                        text = stringResource(R.string.dashboard_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    ProgressBar(fraction = 0f)
                }
            }

            state.isEmpty -> item(key = "empty") {
                GlassCard(modifier = Modifier.contentWidth()) {
                    EmptyState(
                        glyph = "⌕",
                        title = stringResource(R.string.capabilities_empty),
                        body = stringResource(R.string.capabilities_empty_hint),
                    )
                }
            }

            else -> {
                item(key = "counts") {
                    MatrixCounts(state = state, modifier = Modifier.contentWidth())
                }
                matrixGroups(state)
            }
        }
    }
}

/**
 * The four filters.
 *
 * Each carries the count it would show, taken from the whole matrix rather than the current
 * query -- so "Not on this hardware 23" reads as a fact about the device, which is what a
 * reader will take it for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(
    state: MatrixUiState,
    onFilterChange: (MatrixFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SelectChip(
            text = stringResource(R.string.capabilities_filter_all),
            selected = state.filter == MatrixFilter.ALL,
            onClick = { onFilterChange(MatrixFilter.ALL) },
            count = state.total,
        )
        SelectChip(
            text = stringResource(R.string.capabilities_filter_supported),
            selected = state.filter == MatrixFilter.SUPPORTED,
            onClick = { onFilterChange(MatrixFilter.SUPPORTED) },
            count = state.supported,
            accent = colorFor(Support.SUPPORTED),
        )
        SelectChip(
            text = stringResource(R.string.capabilities_filter_api),
            selected = state.filter == MatrixFilter.API_LEVEL,
            onClick = { onFilterChange(MatrixFilter.API_LEVEL) },
            count = state.apiLevelGated,
            accent = MaterialTheme.colorScheme.tertiary,
        )
        SelectChip(
            text = stringResource(R.string.capabilities_filter_hardware),
            selected = state.filter == MatrixFilter.HARDWARE,
            onClick = { onFilterChange(MatrixFilter.HARDWARE) },
            count = state.hardwareAbsent,
            accent = colorFor(Support.UNSUPPORTED),
        )
    }
}

/** "412 of 1,206 rows" plus the glyph legend. */
@Composable
private fun MatrixCounts(
    state: MatrixUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.capabilities_showing, state.shown, state.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        SupportLegend()
    }
}

/**
 * The rows, grouped by lab.
 *
 * Each row is its own lazy item rather than each group being one item holding a Column of
 * rows: a device with a lot of codecs produces several hundred rows in one lab, and a lazy
 * list whose items are groups composes the whole group whenever any part of it is on
 * screen, which is the difference between scrolling smoothly and not.
 */
private fun LazyListScope.matrixGroups(state: MatrixUiState) {
    state.groups.forEach { (lab, rows) ->
        item(key = "head-${lab.id}") {
            LabBanner(lab = lab, count = rows.size, modifier = Modifier.contentWidth())
        }
        items(
            count = rows.size,
            key = { index -> "${lab.id}-$index-${rows[index].capability}" },
        ) { index ->
            MatrixRowView(
                row = rows[index],
                showDivider = index > 0,
                modifier = Modifier.contentWidth(),
            )
        }
    }
}

@Composable
private fun LabBanner(
    lab: Lab,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(top = Spacing.md),
        verticalAlignment = Alignment.Bottom,
    ) {
        Overline(lab.title, modifier = Modifier.weight(1f))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One matrix row.
 *
 * The three columns of Section 18 are laid out vertically rather than as a true table:
 * a capability name, a value and a provenance sentence do not fit side by side at any phone
 * width without truncating the part that carries the meaning. The status column survives as
 * the glyph on the left, and the details column as the line underneath.
 */
@Composable
private fun MatrixRowView(
    row: MatrixRow,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasDetail = row.detail != null
    var expanded by rememberSaveable(row.capability, row.value) { mutableStateOf(false) }
    val description = remember(row) {
        buildString {
            append(row.capability)
            append(": ")
            append(row.value)
            append(". ")
            append(row.support.label)
            append(". ")
            append(row.availability.label)
            append(", ")
            append(row.details)
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) HairlineDivider(alpha = 0.3f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasDetail) {
                        Modifier.clickableRow(onClick = { expanded = !expanded })
                    } else {
                        Modifier
                    }
                )
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            StatusGlyph(
                support = row.support,
                modifier = Modifier
                    .width(20.dp)
                    .padding(top = 1.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.capability,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.value,
                    style = if (LocalMonospaceValues.current) MonoValue else ProportionalValue,
                    color = colorFor(row.support),
                )
                Spacer(Modifier.height(3.dp))
                AvailabilityLine(row = row)
                if (hasDetail && expanded) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = row.detail!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (hasDetail) {
                Spacer(Modifier.width(Spacing.sm))
                FoldIndicator(expanded)
            }
        }
    }
}

/**
 * The line that distinguishes the two unavailabilities.
 *
 * An available row shows only its provenance; an unavailable one leads with which kind of
 * unavailability it is, because that is the distinction Section 18 exists to draw and it
 * has to be the first thing on the line, not buried at the end of a sentence.
 */
@Composable
private fun AvailabilityLine(
    row: MatrixRow,
    modifier: Modifier = Modifier,
) {
    val available = row.availability == Availability.AVAILABLE
    Text(
        text = if (available) row.details else "${row.availability.label} · ${row.details}",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = if (row.availability == Availability.API_LEVEL) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
