package com.devicelab.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.R
import com.devicelab.core.common.Timestamps
import com.devicelab.core.model.SnapshotSummary
import com.devicelab.ui.components.ActionButton
import com.devicelab.ui.components.EmptyState
import com.devicelab.ui.components.GlassCard
import com.devicelab.ui.components.Pill
import com.devicelab.ui.components.ScreenScaffold
import com.devicelab.ui.components.ScreenTitle
import com.devicelab.ui.components.clickableRow
import com.devicelab.ui.components.contentWidth
import com.devicelab.ui.theme.Spacing

/**
 * Saved snapshots: Section 19's save / rename / compare / delete.
 *
 * Comparison is driven by selection rather than by a wizard: tap two snapshots and the
 * compare action appears. Selecting exactly one offers a comparison against the live scan,
 * which is the case a user actually wants most often -- "what changed since I saved this?"
 */
@Composable
fun HistoryScreen(
    onCompare: (Long, Long) -> Unit,
    onCompareWithLive: (Long) -> Unit,
    onOpenSnapshot: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ScreenScaffold(modifier = modifier) {
        item(key = "title") {
            ScreenTitle(
                title = stringResource(R.string.history_title),
                caption = stringResource(R.string.history_caption),
                modifier = Modifier.contentWidth(),
            )
        }

        if (state.snapshots.isEmpty()) {
            if (!state.loading) {
                item(key = "empty") {
                    GlassCard(modifier = Modifier.contentWidth()) {
                        EmptyState(
                            glyph = "◔",
                            title = stringResource(R.string.history_empty),
                            body = stringResource(R.string.history_empty_hint),
                        )
                    }
                }
            }
        } else {
            item(key = "actions") {
                SelectionBar(
                    state = state,
                    onCompare = onCompare,
                    onCompareWithLive = onCompareWithLive,
                    onClearSelection = viewModel::clearSelection,
                    onDeleteAll = viewModel::askDeleteAll,
                    modifier = Modifier.contentWidth(),
                )
            }

            items(
                count = state.snapshots.size,
                key = { index -> state.snapshots[index].id },
            ) { index ->
                val summary = state.snapshots[index]
                SnapshotCard(
                    summary = summary,
                    selected = summary.id in state.selected,
                    onToggleSelect = { viewModel.toggleSelection(summary.id) },
                    onOpen = { onOpenSnapshot(summary.id) },
                    onRename = { viewModel.startRename(summary) },
                    onDelete = { viewModel.delete(summary.id) },
                    modifier = Modifier.contentWidth(),
                )
            }
        }
    }

    state.renaming?.let { target ->
        RenameDialog(
            summary = target,
            onDismiss = viewModel::cancelRename,
            onConfirm = viewModel::confirmRename,
        )
    }

    if (state.confirmingDeleteAll) {
        DeleteAllDialog(
            count = state.snapshots.size,
            onDismiss = viewModel::cancelDeleteAll,
            onConfirm = viewModel::confirmDeleteAll,
        )
    }
}

/**
 * What the current selection allows.
 *
 * The bar states the rule rather than silently disabling a button: with nothing selected it
 * says two are needed, with one it offers the live comparison, with two it offers both. A
 * greyed-out "Compare" with no explanation is the version of this that gets misread as a bug.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    state: HistoryUiState,
    onCompare: (Long, Long) -> Unit,
    onCompareWithLive: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pair = state.comparablePair
    Column(modifier = modifier) {
        Text(
            text = when {
                pair != null -> stringResource(R.string.history_two_selected)
                state.selected.size == 1 -> stringResource(R.string.history_one_selected)
                else -> stringResource(R.string.history_select_two)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (pair != null) {
                ActionButton(
                    text = stringResource(R.string.history_compare),
                    onClick = { onCompare(pair.first, pair.second) },
                    filled = true,
                    leading = "⇄",
                )
            }
            if (state.selected.size == 1 && state.canCompareWithLive) {
                ActionButton(
                    text = stringResource(R.string.history_compare_with_current),
                    onClick = { onCompareWithLive(state.selected.first()) },
                    filled = pair == null,
                    leading = "⇄",
                )
            }
            if (state.selected.isNotEmpty()) {
                ActionButton(
                    text = stringResource(R.string.history_clear_selection),
                    onClick = onClearSelection,
                )
            }
            ActionButton(
                text = stringResource(R.string.history_delete_all),
                onClick = onDeleteAll,
                accent = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * One saved snapshot.
 *
 * The card itself toggles selection and the actions are explicit buttons beneath it. Tapping
 * the card to open and long-pressing to select would hide selection behind a gesture that
 * nothing on screen advertises, and comparison is the main reason this screen exists.
 */
@Composable
private fun SnapshotCard(
    summary: SnapshotSummary,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedLabel = stringResource(R.string.cd_selected)
    val notSelectedLabel = stringResource(R.string.cd_not_selected)
    val captured = remember(summary.capturedAtMillis) {
        Timestamps.readable(summary.capturedAtMillis)
    }
    GlassCard(
        modifier = modifier,
        contentPadding = PaddingValues(Spacing.lg),
        borderAlpha = if (selected) 1f else 0.65f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableRow(onClick = onToggleSelect, role = Role.Checkbox)
                .semantics {
                    this.selected = selected
                    contentDescription = if (selected) selectedLabel else notSelectedLabel
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selected) "◉" else "○",
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = captured,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(text = summary.deviceLabel)
            Pill(
                text = summary.platformLabel,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.history_fact_count, summary.factCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SmallAction(text = stringResource(R.string.history_open), onClick = onOpen)
            SmallAction(text = stringResource(R.string.history_rename), onClick = onRename)
            SmallAction(
                text = stringResource(R.string.history_delete),
                onClick = onDelete,
                destructive = true,
            )
        }
    }
}

/** A compact text action inside a card, sized to a full touch target. */
@Composable
private fun SmallAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier
            .clickableRow(onClick = onClick, role = Role.Button)
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        style = MaterialTheme.typography.labelLarge,
        color = if (destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

/**
 * Renaming a snapshot.
 *
 * Material's `AlertDialog` and `TextField` are used here rather than the hand-built surfaces
 * the rest of the app uses. A dialog needs correct focus handling, IME behaviour and
 * back-press dismissal, and reimplementing those to match a visual style would be trading
 * working accessibility for a rounder corner.
 */
@Composable
private fun RenameDialog(
    summary: SnapshotSummary,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(summary.id) { mutableStateOf(summary.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_rename)) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.history_rename_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Deleting everything is confirmed, because it cannot be undone. */
@Composable
private fun DeleteAllDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_delete_all)) },
        text = { Text(stringResource(R.string.history_delete_all_body, count)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.history_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
