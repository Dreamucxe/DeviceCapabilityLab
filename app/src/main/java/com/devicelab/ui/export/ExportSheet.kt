package com.devicelab.ui.export

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.R
import com.devicelab.core.common.Format
import com.devicelab.data.export.ExportFormat
import com.devicelab.ui.components.ActionButton
import com.devicelab.ui.components.GlassCard
import com.devicelab.ui.components.Overline
import com.devicelab.ui.components.ProgressBar
import com.devicelab.ui.components.SelectChip
import com.devicelab.ui.theme.LocalMonospaceValues
import com.devicelab.ui.theme.MonoValue
import com.devicelab.ui.theme.ProportionalValue
import com.devicelab.ui.theme.Radii
import com.devicelab.ui.theme.Spacing

/**
 * Export, as a bottom sheet over the dashboard.
 *
 * A sheet rather than a screen because exporting is an action on the scan already on screen,
 * not a place to be; the report is rendered as soon as the sheet opens so the size and value
 * count shown are the real ones, measured from the document that will actually be written.
 *
 * The preview shows the beginning of the document rather than all of it. A JSON report of a
 * modern device runs past a megabyte, and a `Text` holding that would spend the sheet's whole
 * open animation laying out text nobody will read at that size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    onDismiss: () -> Unit,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    // The chooser is launched from the intent the ViewModel produced, then cleared. Without
    // that acknowledgement a recomposition -- of which a sheet has several while opening --
    // would start a second chooser over the first.
    LaunchedEffect(state.shareIntent) {
        val intent = state.shareIntent ?: return@LaunchedEffect
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // A device with nothing able to receive ACTION_SEND. Nothing was written that
            // needs undoing; the file stays in the cache and the sheet stays open.
        }
        viewModel.shareLaunched()
    }

    ModalBottomSheet(
        onDismissRequest = {
            // Clear the copied/failed feedback on the way out. The ViewModel outlives the
            // sheet -- it is scoped to the destination, not to the sheet -- so without this
            // a second open would begin by reporting the result of the first.
            viewModel.acknowledge()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.export_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.export_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.xl))
            Overline(stringResource(R.string.export_format))
            Spacer(Modifier.height(Spacing.sm))
            FormatChips(
                selected = state.format,
                onSelect = viewModel::selectFormat,
            )

            Spacer(Modifier.height(Spacing.lg))
            when {
                state.rendering && state.document == null -> {
                    ProgressBar(fraction = 0f)
                }

                state.document != null -> {
                    DocumentPreview(state = state)
                }

                !state.ready -> {
                    Text(
                        text = stringResource(R.string.export_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.export_failed) + ": " + state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.copied) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.export_copied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(Spacing.xl))
            Actions(
                canAct = state.document != null && !state.rendering,
                canCopy = state.document != null &&
                    !state.rendering &&
                    state.format != ExportFormat.HTML,
                onShare = viewModel::share,
                onCopy = viewModel::copy,
                onClose = {
                    viewModel.acknowledge()
                    onDismiss()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatChips(
    selected: ExportFormat,
    onSelect: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ExportFormat.entries.forEach { format ->
            SelectChip(
                text = format.label,
                selected = format == selected,
                onClick = { onSelect(format) },
            )
        }
    }
}

/** The filename, the real byte size, and the first few lines of what will be written. */
@Composable
private fun DocumentPreview(
    state: ExportUiState,
    modifier: Modifier = Modifier,
) {
    val document = state.document ?: return
    val head = remember(document) { document.content.take(PREVIEW_CHARS) }
    val size = remember(document) { Format.bytes(document.sizeBytes.toLong()) }
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = document.filename,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.export_size, size, state.values),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = head,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
            style = if (LocalMonospaceValues.current) MonoValue else ProportionalValue,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    canAct: Boolean,
    canCopy: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ActionButton(
            text = stringResource(R.string.export_share),
            onClick = onShare,
            enabled = canAct,
            filled = true,
            leading = "↥",
        )
        // Copying a full HTML document into a message is not useful, so the action is absent
        // for HTML rather than present and failing. The plain-text renderer is the one for
        // pasting, and it is one chip away.
        if (canCopy) {
            ActionButton(
                text = stringResource(R.string.export_copy),
                onClick = onCopy,
                leading = "⧉",
            )
        }
        ActionButton(
            text = stringResource(R.string.action_close),
            onClick = onClose,
        )
    }
}

/** How much of the document the preview shows. Two screens' worth at this type size. */
private const val PREVIEW_CHARS = 1600
