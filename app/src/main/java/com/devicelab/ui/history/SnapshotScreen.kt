package com.devicelab.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.R
import com.devicelab.core.common.Timestamps
import com.devicelab.core.model.DomainStatus
import com.devicelab.core.model.FactRow
import com.devicelab.core.model.Snapshot
import com.devicelab.core.model.Support
import com.devicelab.ui.components.EmptyState
import com.devicelab.ui.components.FeatureCard
import com.devicelab.ui.components.FoldIndicator
import com.devicelab.ui.components.GlassCard
import com.devicelab.ui.components.HairlineDivider
import com.devicelab.ui.components.Motion
import com.devicelab.ui.components.Overline
import com.devicelab.ui.components.Pill
import com.devicelab.ui.components.ProgressBar
import com.devicelab.ui.components.ScreenScaffold
import com.devicelab.ui.components.SearchField
import com.devicelab.ui.components.StatusGlyph
import com.devicelab.ui.components.clickableRow
import com.devicelab.ui.components.contentWidth
import com.devicelab.ui.navigation.DetailTopBar
import com.devicelab.ui.theme.LocalMonospaceValues
import com.devicelab.ui.theme.LocalShowProvenance
import com.devicelab.ui.theme.MonoValue
import com.devicelab.ui.theme.ProportionalValue
import com.devicelab.ui.theme.Spacing
import com.devicelab.ui.theme.colorFor

/**
 * A saved snapshot, read back.
 *
 * This screen renders stored [FactRow]s rather than live [com.devicelab.core.model.Fact]s, so
 * it cannot reuse [com.devicelab.ui.components.FactRowView]. That is the point: a stored row
 * carries the provenance *text* that was written at capture time instead of a `Provenance`
 * object to be re-rendered, so an old snapshot keeps explaining itself in the words the app
 * used then, even after the wording changes.
 */
@Composable
fun SnapshotScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SnapshotViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot

    ScreenScaffold(
        modifier = modifier,
        topContent = {
            DetailTopBar(
                title = snapshot?.name ?: stringResource(R.string.snapshot_title),
                subtitle = snapshot?.let { "${it.deviceLabel} · ${it.platformLabel}" },
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

            snapshot == null -> item(key = "missing") {
                GlassCard(modifier = Modifier.contentWidth()) {
                    EmptyState(
                        glyph = "?",
                        title = stringResource(R.string.snapshot_not_found),
                        body = stringResource(R.string.snapshot_not_found_hint),
                    )
                }
            }

            else -> {
                item(key = "header") {
                    SnapshotHeader(snapshot = snapshot, modifier = Modifier.contentWidth())
                }

                if (state.scorecard.isNotEmpty()) {
                    item(key = "scorecard") {
                        StoredScorecard(
                            scorecard = state.scorecard,
                            modifier = Modifier.contentWidth(),
                        )
                    }
                }

                item(key = "search") {
                    Column(modifier = Modifier.contentWidth()) {
                        SearchField(
                            query = state.query,
                            onQueryChange = viewModel::onQueryChange,
                            hint = stringResource(R.string.snapshot_search_hint),
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = if (state.query.isBlank()) {
                                stringResource(R.string.lab_values_total, state.total)
                            } else {
                                stringResource(
                                    R.string.snapshot_matched,
                                    state.matched,
                                    state.total,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.groups.isEmpty()) {
                    item(key = "empty") {
                        GlassCard(modifier = Modifier.contentWidth()) {
                            EmptyState(
                                glyph = "⌕",
                                title = stringResource(R.string.lab_empty),
                                body = stringResource(R.string.lab_empty_hint),
                            )
                        }
                    }
                } else {
                    storedGroups(state.groups)
                }
            }
        }
    }
}

/** What was captured, when, and on what. */
@Composable
private fun SnapshotHeader(
    snapshot: Snapshot,
    modifier: Modifier = Modifier,
) {
    val captured = remember(snapshot.capturedAtMillis) {
        Timestamps.readable(snapshot.capturedAtMillis)
    }
    FeatureCard(modifier = modifier) {
        Overline(snapshot.deviceLabel)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.snapshot_captured, captured),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Pill(snapshot.platformLabel, color = MaterialTheme.colorScheme.tertiary)
        }
        if (snapshot.fingerprint.isNotBlank()) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = snapshot.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.snapshot_stored_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The stored scan's own scorecard.
 *
 * Recomputed from the stored rows by the same [com.devicelab.core.model.Scorecard] rule the
 * dashboard uses, not stored alongside them. That keeps one definition of what "partially
 * supported" means; the inputs are frozen, the rule is shared.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoredScorecard(
    scorecard: List<DomainStatus>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Overline(stringResource(R.string.dashboard_summary_title))
        Spacer(Modifier.height(Spacing.md))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            scorecard.forEach { status ->
                val description = stringResource(
                    R.string.cd_domain_status,
                    status.domain.title,
                    status.support.label,
                    status.summary,
                )
                Pill(
                    text = status.domain.title,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = description
                    },
                    color = colorFor(status.support),
                    leading = {
                        StatusGlyph(
                            support = status.support,
                            size = MaterialTheme.typography.labelMedium.fontSize,
                        )
                    },
                )
            }
        }
    }
}

/**
 * The stored rows, grouped by the section they were read under.
 *
 * One lazy item per row rather than per group, for the same reason the capability matrix does
 * it: a snapshot of a modern device holds well over a thousand rows and one group of them can
 * be several hundred codecs.
 */
private fun LazyListScope.storedGroups(groups: List<StoredGroup>) {
    groups.forEachIndexed { groupIndex, group ->
        item(key = "group-$groupIndex") {
            Row(
                modifier = Modifier.contentWidth().padding(top = Spacing.md),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(group.lab.title, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = group.sectionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = group.rows.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(
            count = group.rows.size,
            key = { index -> "row-$groupIndex-${group.rows[index].key}" },
        ) { index ->
            StoredRowView(
                row = group.rows[index],
                showDivider = index > 0,
                modifier = Modifier.contentWidth(),
            )
        }
    }
}

/** One stored label/value row, expandable when it kept a technical detail. */
@Composable
private fun StoredRowView(
    row: FactRow,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasDetail = row.detail != null
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    val showProvenance = LocalShowProvenance.current
    val description = remember(row, showProvenance) {
        buildString {
            append(row.label)
            append(": ")
            append(row.value)
            if (row.support != Support.INFORMATIONAL) {
                append(". Status: ")
                append(row.support.label)
            }
            append(". ")
            append(row.provenance.replace(" · ", ", "))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) HairlineDivider(alpha = 0.35f)
        Column(
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
        ) {
            Row(verticalAlignment = Alignment.Top) {
                StatusGlyph(
                    support = row.support,
                    modifier = Modifier.width(20.dp).padding(top = 1.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = row.value,
                        style = if (LocalMonospaceValues.current) MonoValue else ProportionalValue,
                        color = when (row.support) {
                            Support.INFORMATIONAL -> MaterialTheme.colorScheme.onSurface
                            Support.NOT_EXPOSED, Support.UNKNOWN ->
                                MaterialTheme.colorScheme.onSurfaceVariant

                            else -> colorFor(row.support)
                        },
                    )
                    if (showProvenance) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = row.provenance,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (hasDetail) {
                    Spacer(Modifier.width(Spacing.sm))
                    FoldIndicator(expanded)
                }
            }
            if (hasDetail) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = Motion.resize()) +
                        fadeIn(animationSpec = Motion.fade()),
                    exit = shrinkVertically(animationSpec = Motion.resize()) +
                        fadeOut(animationSpec = Motion.fade()),
                ) {
                    Text(
                        text = row.detail.orEmpty(),
                        modifier = Modifier.padding(start = 28.dp, top = Spacing.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
