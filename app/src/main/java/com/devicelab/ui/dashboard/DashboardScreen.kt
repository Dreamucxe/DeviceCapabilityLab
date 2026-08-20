package com.devicelab.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.R
import com.devicelab.core.common.Timestamps
import com.devicelab.core.model.DeviceIdentity
import com.devicelab.core.model.DomainStatus
import com.devicelab.core.model.Lab
import com.devicelab.data.repo.ScanState
import com.devicelab.ui.components.ActionButton
import com.devicelab.ui.components.EmptyState
import com.devicelab.ui.components.FeatureCard
import com.devicelab.ui.components.GlassCard
import com.devicelab.ui.components.Overline
import com.devicelab.ui.components.Pill
import com.devicelab.ui.components.ProgressBar
import com.devicelab.ui.components.ScreenScaffold
import com.devicelab.ui.components.ScreenTitle
import com.devicelab.ui.components.StatusGlyph
import com.devicelab.ui.components.SupportLegend
import com.devicelab.ui.components.clickableRow
import com.devicelab.ui.components.contentWidth
import com.devicelab.ui.theme.MetricStyle
import com.devicelab.ui.theme.Spacing
import com.devicelab.ui.theme.colorFor

/**
 * The dashboard.
 *
 * Section 3 is explicit that there must be no invented overall score, so there is none. What
 * the top of the screen shows instead is the eight domains of Section 3, each with the
 * status the detection actually produced and the counts behind it. A reader who wants to
 * know why Graphics is "partly supported" can see that it is 9 of 14 and go to the lab.
 */
@Composable
fun DashboardScreen(
    onOpenLab: (Lab) -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val scan by viewModel.scanState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    ScreenScaffold(modifier = modifier) {
        item(key = "title") {
            ScreenTitle(
                title = stringResource(R.string.app_name_long),
                caption = stringResource(R.string.app_tagline),
                modifier = Modifier.contentWidth(),
            )
        }

        item(key = "device") {
            DeviceHeader(
                identity = viewModel.identity,
                modifier = Modifier.contentWidth(),
            )
        }

        when (val current = scan) {
            is ScanState.Idle, is ScanState.Scanning -> scanningItems(current)

            is ScanState.Failed -> item(key = "failed") {
                GlassCard(modifier = Modifier.contentWidth()) {
                    EmptyState(
                        glyph = "!",
                        title = stringResource(R.string.scan_failed),
                        body = current.reason,
                        action = {
                            ActionButton(
                                text = stringResource(R.string.action_retry),
                                onClick = viewModel::rescan,
                                filled = true,
                            )
                        },
                    )
                }
            }

            is ScanState.Ready -> readyItems(
                state = current,
                ui = ui,
                onRescan = viewModel::rescan,
                onSave = viewModel::saveSnapshot,
                onExport = onOpenExport,
                onOpenLab = onOpenLab,
            )
        }
    }
}

/** The scan in progress, or about to start. */
private fun LazyListScope.scanningItems(state: ScanState) {
    item(key = "progress") {
        val fraction = (state as? ScanState.Scanning)?.fraction ?: 0f
        val current = (state as? ScanState.Scanning)?.current
        val percent = (fraction * 100).toInt()
        GlassCard(modifier = Modifier.contentWidth()) {
            Text(
                text = stringResource(R.string.dashboard_scanning),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = if (current != null) {
                    stringResource(R.string.dashboard_scanning_lab, current.title)
                } else {
                    " "
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            ProgressBar(
                fraction = fraction,
                label = stringResource(R.string.cd_scan_progress, percent),
            )
        }
    }
}

/** A finished scan. */
private fun LazyListScope.readyItems(
    state: ScanState.Ready,
    ui: DashboardUiState,
    onRescan: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onOpenLab: (Lab) -> Unit,
) {
    item(key = "summary-header") {
        Column(modifier = Modifier.contentWidth()) {
            Text(
                text = stringResource(R.string.dashboard_summary_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.dashboard_summary_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            SupportLegend()
        }
    }

    // Two cards to a row. A LazyVerticalGrid cannot nest inside a LazyColumn, and the
    // scorecard is a fixed eight entries, so pairing them is both simpler and cheaper than
    // a second scrollable.
    val pairs = state.profile.scorecard.chunked(2)
    items(
        count = pairs.size,
        key = { index -> "domains-$index" },
    ) { index ->
        Row(
            modifier = Modifier.contentWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            pairs[index].forEach { status ->
                DomainCard(status = status, modifier = Modifier.weight(1f))
            }
            // Keeps a lone final card at half width rather than stretching it, so the grid
            // stays a grid when the domain count is odd.
            if (pairs[index].size == 1) Spacer(Modifier.weight(1f))
        }
    }

    item(key = "actions") {
        DashboardActions(
            ui = ui,
            onRescan = onRescan,
            onSave = onSave,
            onExport = onExport,
            modifier = Modifier.contentWidth(),
        )
    }

    item(key = "meta") {
        ScanMeta(state = state, modifier = Modifier.contentWidth())
    }

    val notes = state.profile.reports.filter { it.notes.isNotEmpty() }
    if (notes.isNotEmpty()) {
        item(key = "notes") {
            GlassCard(modifier = Modifier.contentWidth()) {
                Overline(stringResource(R.string.dashboard_notes))
                Spacer(Modifier.height(Spacing.sm))
                notes.forEach { report ->
                    report.notes.forEach { note ->
                        NoteRow(
                            lab = report.lab,
                            note = note,
                            onClick = { onOpenLab(report.lab) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The device this report is about.
 *
 * The build fingerprint is shown in full, wrapped. It is the one string that identifies
 * exactly which OS build produced every other value on the screen, which is what makes a
 * report comparable with another one -- and Section 20 permits it precisely because it
 * describes the build rather than the person using it.
 */
@Composable
private fun DeviceHeader(
    identity: DeviceIdentity,
    modifier: Modifier = Modifier,
) {
    FeatureCard(modifier = modifier) {
        Overline(identity.manufacturer.ifBlank { "Unknown maker" })
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = identity.model.ifBlank { "Unknown model" },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Pill("Android ${identity.androidRelease}")
            Pill("API ${identity.apiLevel}", color = MaterialTheme.colorScheme.tertiary)
        }
        if (identity.fingerprint.isNotBlank()) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = identity.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One of the eight domain cards.
 *
 * The card states the status in words as well as by glyph and colour, and the fraction under
 * it is the evidence: "9 / 14 capabilities". A card that said only "Partial" would be a
 * judgement without a basis.
 */
@Composable
private fun DomainCard(
    status: DomainStatus,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        R.string.cd_domain_status,
        status.domain.title,
        status.support.label,
        status.summary,
    )
    GlassCard(
        modifier = modifier
            .heightIn(min = 118.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentPadding = PaddingValues(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(support = status.support)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = status.domain.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        if (status.total > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = status.supported.toString(),
                    style = MetricStyle,
                    color = colorFor(status.support),
                )
                Text(
                    text = " / ${status.total}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = status.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardActions(
    ui: DashboardUiState,
    onRescan: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ActionButton(
                text = stringResource(R.string.dashboard_save),
                onClick = onSave,
                enabled = !ui.saving,
                filled = true,
                leading = "＋",
            )
            ActionButton(
                text = stringResource(R.string.dashboard_export),
                onClick = onExport,
                leading = "↥",
            )
            ActionButton(
                text = stringResource(R.string.dashboard_rescan),
                onClick = onRescan,
                leading = "↻",
            )
        }
        if (ui.justSaved) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.dashboard_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (ui.error != null) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = ui.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** How long the scan took and how much it produced. */
@Composable
private fun ScanMeta(
    state: ScanState.Ready,
    modifier: Modifier = Modifier,
) {
    val facts = state.profile.allFacts().size
    Column(modifier = modifier) {
        Text(
            text = stringResource(
                R.string.dashboard_facts_read,
                facts,
                state.profile.reports.size,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.dashboard_scan_duration,
                Timestamps.duration(state.durationMillis),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A detection note, tappable through to the lab that raised it.
 *
 * Notes are where a lab says something the fact rows cannot -- that a vendor API returned an
 * empty list where the platform guarantees at least one entry, that a query was skipped
 * because it would have required a permission the app does not hold. Section 29 requires
 * these to surface rather than be swallowed.
 */
@Composable
private fun NoteRow(
    lab: Lab,
    note: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickableRow(onClick = onClick)
            .padding(vertical = Spacing.sm),
    ) {
        Text(
            text = lab.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
