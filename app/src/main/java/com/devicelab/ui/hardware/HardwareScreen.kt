package com.devicelab.ui.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.R
import com.devicelab.core.model.Lab
import com.devicelab.core.model.Support
import com.devicelab.ui.components.GlassCard
import com.devicelab.ui.components.Pill
import com.devicelab.ui.components.ProgressBar
import com.devicelab.ui.components.ScreenScaffold
import com.devicelab.ui.components.ScreenTitle
import com.devicelab.ui.components.StatusGlyph
import com.devicelab.ui.components.clickableRow
import com.devicelab.ui.components.contentWidth
import com.devicelab.ui.theme.Spacing
import com.devicelab.ui.theme.colorFor

/**
 * The lab index: fifteen inspection areas, each with its own roll-up.
 *
 * A lab badge is derived the same way a dashboard domain is -- over the lab's verdict facts,
 * ignoring measurements -- so a lab that only measures (Memory reports sizes, not yes/no
 * capabilities) shows "Reported" rather than a tick it did not earn.
 */
@Composable
fun HardwareScreen(
    onOpenLab: (Lab) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HardwareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ScreenScaffold(modifier = modifier) {
        item(key = "title") {
            ScreenTitle(
                title = stringResource(R.string.hardware_title),
                caption = stringResource(R.string.hardware_caption),
                modifier = Modifier.contentWidth(),
            )
        }

        if (state.loading) {
            item(key = "loading") {
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
        } else {
            item(key = "total") {
                Text(
                    text = stringResource(R.string.hardware_total, state.totalFacts),
                    modifier = Modifier.contentWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(
                count = state.labs.size,
                key = { index -> state.labs[index].lab.id },
            ) { index ->
                LabCard(
                    summary = state.labs[index],
                    onClick = { onOpenLab(state.labs[index].lab) },
                    modifier = Modifier.contentWidth(),
                )
            }
        }
    }
}

/**
 * One lab in the index.
 *
 * The counts are the card's justification: "12 of 19 capabilities" says where the badge came
 * from, and the value count says how much there is to read behind it. A card with a badge and
 * no numbers would be asking to be trusted.
 */
@Composable
private fun LabCard(
    summary: LabSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val counts = if (summary.verdicts > 0) {
        stringResource(
            R.string.hardware_lab_counts,
            summary.affirmative,
            summary.verdicts,
            summary.facts,
        )
    } else {
        stringResource(R.string.hardware_lab_values, summary.facts)
    }
    val description = remember(summary, counts) {
        "${summary.lab.title}. ${summary.support.label}. ${summary.lab.blurb}. $counts"
    }
    GlassCard(
        modifier = modifier
            .clickableRow(onClick = onClick, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentPadding = PaddingValues(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(
                support = summary.support,
                size = MaterialTheme.typography.titleLarge.fontSize,
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.lab.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary.lab.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(
                text = summary.support.label,
                color = colorFor(summary.support),
            )
            Text(
                text = counts,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.hasNotes) {
                Pill(
                    text = stringResource(R.string.hardware_lab_has_notes),
                    color = colorFor(Support.PARTIAL),
                )
            }
        }
    }
}
