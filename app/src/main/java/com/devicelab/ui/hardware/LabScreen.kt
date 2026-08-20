package com.devicelab.ui.hardware

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.R
import com.devicelab.core.model.Section
import com.devicelab.ui.components.EmptyState
import com.devicelab.ui.components.FactList
import com.devicelab.ui.components.GlassCard
import com.devicelab.ui.components.Overline
import com.devicelab.ui.components.ProgressBar
import com.devicelab.ui.components.ScreenScaffold
import com.devicelab.ui.components.SearchField
import com.devicelab.ui.components.SectionCard
import com.devicelab.ui.components.contentWidth
import com.devicelab.ui.navigation.DetailTopBar
import com.devicelab.ui.theme.Spacing

/**
 * One lab's full report.
 *
 * The section tree is rendered as it came out of the detector, nesting and all: a camera
 * report is one child section per camera, a codec report one per codec. Flattening it would
 * make "Hardware level: FULL" ambiguous across five cameras, and this screen's whole job is
 * to be unambiguous about which piece of hardware answered.
 */
@Composable
fun LabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Fold state lives in the composition rather than the ViewModel: it is a view concern
    // and it is keyed by section id, so it survives a filter change (which replaces the
    // section objects) but not a navigation away, which is the behaviour a reader expects.
    val folded = remember { mutableStateMapOf<String, Boolean>() }

    ScreenScaffold(
        modifier = modifier,
        topContent = {
            DetailTopBar(
                title = state.lab?.title ?: stringResource(R.string.lab_unknown),
                subtitle = state.lab?.blurb,
                onBack = onBack,
            )
        },
    ) {
        when {
            state.lab == null -> item(key = "unknown") {
                GlassCard(modifier = Modifier.contentWidth()) {
                    EmptyState(
                        glyph = "?",
                        title = stringResource(R.string.lab_unknown),
                        body = stringResource(R.string.lab_unknown_hint),
                    )
                }
            }

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

            state.total == 0 -> item(key = "no-data") {
                GlassCard(modifier = Modifier.contentWidth()) {
                    EmptyState(
                        glyph = "—",
                        title = stringResource(R.string.lab_no_data),
                        body = stringResource(R.string.lab_no_data_hint),
                    )
                }
            }

            else -> {
                item(key = "search") {
                    Column(modifier = Modifier.contentWidth()) {
                        SearchField(
                            query = state.query,
                            onQueryChange = viewModel::onQueryChange,
                            hint = stringResource(R.string.lab_search_hint),
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        LabCounts(state = state)
                    }
                }

                if (state.notes.isNotEmpty()) {
                    item(key = "notes") {
                        NotesCard(notes = state.notes, modifier = Modifier.contentWidth())
                    }
                }

                if (state.sections.isEmpty()) {
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
                    sectionItems(
                        sections = state.sections,
                        expanded = { id -> folded[id] ?: true },
                        onToggle = { id -> folded[id] = !(folded[id] ?: true) },
                    )
                }
            }
        }
    }
}

/**
 * How much of the lab is on screen.
 *
 * When a query is active this reads "31 of 214 values match". When rows have been hidden by
 * the "show unavailable" setting it says so too -- hiding rows must not also hide that they
 * were hidden, or the device looks more capable than it reported.
 */
@Composable
private fun LabCounts(
    state: LabUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (state.query.isBlank()) {
                stringResource(R.string.lab_values_total, state.total)
            } else {
                stringResource(R.string.lab_matched, state.matched, state.total)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.hidden > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.lab_hidden, state.hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** A lab's non-fatal detection notes. */
@Composable
private fun NotesCard(
    notes: List<String>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Overline(stringResource(R.string.lab_notes))
        Spacer(Modifier.height(Spacing.sm))
        notes.forEachIndexed { index, note ->
            if (index > 0) Spacer(Modifier.height(Spacing.sm))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The section tree as lazy items.
 *
 * Each top-level section is one lazy item holding a card. That is a deliberate exception to
 * the row-per-item rule used by the capability matrix: a section's card draws one border
 * around its rows, and a card cannot be split across lazy items. Sections are bounded in
 * size by the detector that produced them, and the fold control exists for the few that are
 * not -- the codec lab's encoder list, most of all.
 */
private fun LazyListScope.sectionItems(
    sections: List<Section>,
    expanded: (String) -> Boolean,
    onToggle: (String) -> Unit,
) {
    sections.forEach { section ->
        item(key = "section-${section.id}") {
            LabSectionCard(
                section = section,
                path = section.id,
                expanded = expanded,
                onToggle = onToggle,
                modifier = Modifier.contentWidth(),
            )
        }
    }
}

/**
 * One section, and its children beneath it.
 *
 * Child sections are drawn inside the parent's card as sub-headed groups rather than as
 * cards of their own. Nesting cards three deep produces a stack of borders that reads as
 * clutter, and the parent's fold already governs the whole subtree.
 */
@Composable
private fun LabSectionCard(
    section: Section,
    path: String,
    expanded: (String) -> Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = remember(section) { section.allFacts().size }
    SectionCard(
        title = section.title,
        subtitle = section.subtitle,
        expanded = expanded(path),
        onToggle = { onToggle(path) },
        modifier = modifier,
        trailing = {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        if (section.facts.isNotEmpty()) {
            FactList(facts = section.facts)
        }
        section.children.forEach { child ->
            ChildSection(
                section = child,
                depth = 1,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
    }
}

/** A nested section inside its parent's card. */
@Composable
private fun ChildSection(
    section: Section,
    depth: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Overline(
            text = section.title,
            color = MaterialTheme.colorScheme.primary,
        )
        if (section.subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        if (section.facts.isNotEmpty()) {
            FactList(facts = section.facts)
        }
        // Indented one step per level, capped: past two levels the indent costs more
        // readable width than the structure it conveys is worth on a phone.
        val indent = if (depth >= 2) Spacing.md else Spacing.sm
        section.children.forEach { grandchild ->
            ChildSection(
                section = grandchild,
                depth = depth + 1,
                modifier = Modifier.padding(start = indent, top = Spacing.md),
            )
        }
    }
}
