package com.devicelab.ui.history

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.R
import com.devicelab.core.common.Timestamps
import com.devicelab.core.model.ChangeKind
import com.devicelab.core.model.FactDelta
import com.devicelab.core.model.Lab
import com.devicelab.core.model.Snapshot
import com.devicelab.core.model.SnapshotDiff
import com.devicelab.core.model.Support
import com.devicelab.ui.components.ActionButton
import com.devicelab.ui.components.EmptyState
import com.devicelab.ui.components.FeatureCard
import com.devicelab.ui.components.GlassCard
import com.devicelab.ui.components.HairlineDivider
import com.devicelab.ui.components.Overline
import com.devicelab.ui.components.Pill
import com.devicelab.ui.components.ProgressBar
import com.devicelab.ui.components.ScreenScaffold
import com.devicelab.ui.components.SelectChip
import com.devicelab.ui.components.contentWidth
import com.devicelab.ui.navigation.DetailTopBar
import com.devicelab.ui.theme.LocalMonospaceValues
import com.devicelab.ui.theme.MonoValue
import com.devicelab.ui.theme.ProportionalValue
import com.devicelab.ui.theme.Spacing
import com.devicelab.ui.theme.colorFor

/**
 * Two snapshots, compared.
 *
 * Section 19 asks for ADDED / REMOVED / CHANGED / UNCHANGED to be distinguished, and they
 * are -- by glyph, by colour and by word. Unchanged rows are collapsed behind a toggle
 * because on two scans of the same device they are almost everything; the count is always
 * visible whether or not the rows are.
 */
@Composable
fun CompareScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ScreenScaffold(
        modifier = modifier,
        topContent = {
            DetailTopBar(
                title = stringResource(R.string.compare_title),
                subtitle = state.diff?.headline,
                onBack = onBack,
            )
        },
    ) {
        when {
            state.loading -> item(key = "loading") {
                GlassCard(modifier = Modifier.contentWidth()) {
                    Text(
                        text = stringResource(R.string.compare_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    ProgressBar(fraction = 0f)
                }
            }

            state.diff == null -> item(key = "unavailable") {
                GlassCard(modifier = Modifier.contentWidth()) {
                    EmptyState(
                        glyph = "!",
                        title = stringResource(R.string.compare_unavailable),
                        body = stringResource(R.string.compare_unavailable_hint),
                        action = {
                            ActionButton(
                                text = stringResource(R.string.action_retry),
                                onClick = viewModel::retry,
                                filled = true,
                            )
                        },
                    )
                }
            }

            else -> {
                val diff = state.diff!!
                item(key = "summary") {
                    DiffSummary(diff = diff, modifier = Modifier.contentWidth())
                }
                item(key = "sides") {
                    SidesCard(diff = diff, modifier = Modifier.contentWidth())
                }
                item(key = "toggle") {
                    Row(modifier = Modifier.contentWidth()) {
                        SelectChip(
                            text = stringResource(
                                R.string.compare_show_unchanged_count,
                                diff.unchanged,
                            ),
                            selected = state.showUnchanged,
                            onClick = viewModel::toggleUnchanged,
                        )
                    }
                }
                if (!diff.hasDifferences && !state.showUnchanged) {
                    item(key = "identical") {
                        GlassCard(modifier = Modifier.contentWidth()) {
                            EmptyState(
                                glyph = "=",
                                title = stringResource(R.string.compare_identical),
                                body = stringResource(R.string.compare_identical_hint),
                            )
                        }
                    }
                } else {
                    deltaGroups(state.groups)
                }
            }
        }
    }
}

/** The four counts, each in its own colour and stated in words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiffSummary(
    diff: SnapshotDiff,
    modifier: Modifier = Modifier,
) {
    FeatureCard(modifier = modifier) {
        Overline(stringResource(R.string.compare_summary))
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = diff.headline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.md))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Pill(
                text = "${diff.changed} ${stringResource(R.string.compare_changed).lowercase()}",
                color = colorFor(Support.PARTIAL),
            )
            Pill(
                text = "${diff.added} ${stringResource(R.string.compare_added).lowercase()}",
                color = colorFor(Support.SUPPORTED),
            )
            Pill(
                text = "${diff.removed} ${stringResource(R.string.compare_removed).lowercase()}",
                color = colorFor(Support.UNSUPPORTED),
            )
            Pill(
                text = "${diff.unchanged} ${stringResource(R.string.compare_unchanged).lowercase()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!diff.sameDevice) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.compare_different_devices),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/**
 * Which snapshot is which.
 *
 * Every row on this screen reads "before → after", so the screen has to say what before and
 * after are. Without it a diff between two devices is unreadable in either direction.
 */
@Composable
private fun SidesCard(
    diff: SnapshotDiff,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        SideRow(
            label = stringResource(R.string.compare_left),
            snapshot = diff.left,
        )
        HairlineDivider(alpha = 0.35f)
        SideRow(
            label = stringResource(R.string.compare_right),
            snapshot = diff.right,
        )
    }
}

@Composable
private fun SideRow(
    label: String,
    snapshot: Snapshot,
    modifier: Modifier = Modifier,
) {
    val captured = remember(snapshot.capturedAtMillis) {
        Timestamps.readable(snapshot.capturedAtMillis)
    }
    Column(modifier = modifier.padding(vertical = Spacing.sm)) {
        Overline(label)
        Spacer(Modifier.height(2.dp))
        Text(
            text = snapshot.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${snapshot.deviceLabel} · ${snapshot.platformLabel} · $captured",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Deltas grouped by lab, one lazy item per row. */
private fun LazyListScope.deltaGroups(groups: List<Pair<Lab, List<FactDelta>>>) {
    groups.forEach { (lab, deltas) ->
        item(key = "delta-head-${lab.id}") {
            Row(
                modifier = Modifier.contentWidth().padding(top = Spacing.md),
                verticalAlignment = Alignment.Bottom,
            ) {
                Overline(lab.title, modifier = Modifier.weight(1f))
                Text(
                    text = deltas.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(
            count = deltas.size,
            key = { index -> "delta-${lab.id}-${deltas[index].key}" },
        ) { index ->
            DeltaRow(
                delta = deltas[index],
                showDivider = index > 0,
                modifier = Modifier.contentWidth(),
            )
        }
    }
}

/**
 * One changed fact.
 *
 * The value line is "before → after" for a change, and the single value for an addition or
 * a removal. A provenance-only change gets its reason spelled out underneath, because the
 * two values are identical and the row would otherwise look like a mistake.
 */
@Composable
private fun DeltaRow(
    delta: FactDelta,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = colorForKind(delta.kind)
    val description = remember(delta) {
        "${delta.kind.label}. ${delta.label}, ${delta.sectionTitle}. ${delta.summary}"
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) HairlineDivider(alpha = 0.3f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = delta.kind.glyph,
                modifier = Modifier.width(20.dp),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = delta.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = delta.summary,
                    style = if (LocalMonospaceValues.current) MonoValue else ProportionalValue,
                    color = accent,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = delta.sectionTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                delta.reason?.let { reason ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A change's colour.
 *
 * Deliberately reusing the support palette: added is the same green as supported, removed the
 * same red as unsupported. A second colour vocabulary for the same three ideas would be one
 * more thing to learn for no gain.
 */
@Composable
private fun colorForKind(kind: ChangeKind): Color = when (kind) {
    ChangeKind.ADDED -> colorFor(Support.SUPPORTED)
    ChangeKind.REMOVED -> colorFor(Support.UNSUPPORTED)
    ChangeKind.CHANGED -> colorFor(Support.PARTIAL)
    ChangeKind.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
}
