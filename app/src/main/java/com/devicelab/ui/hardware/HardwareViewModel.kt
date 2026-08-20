package com.devicelab.ui.hardware

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import com.devicelab.core.model.Support
import com.devicelab.data.repo.ScanCoordinator
import com.devicelab.data.repo.ScanState
import com.devicelab.data.settings.SettingsRepository
import com.devicelab.ui.asUiState
import com.devicelab.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * One row of the lab index.
 *
 * @param support the lab's own roll-up, over its verdict facts only
 * @param facts how many values the lab produced in total, measurements included
 * @param affirmative how many of its verdicts came back yes or partly
 * @param verdicts how many of its facts are verdicts rather than measurements
 */
data class LabSummary(
    val lab: Lab,
    val support: Support,
    val facts: Int,
    val affirmative: Int,
    val verdicts: Int,
    val hasNotes: Boolean,
)

data class HardwareUiState(
    val loading: Boolean = true,
    val labs: List<LabSummary> = emptyList(),
    val totalFacts: Int = 0,
)

/**
 * The lab index.
 *
 * Each lab's badge is rolled up the same way the dashboard rolls up a domain: over the
 * facts that are verdicts, ignoring measurements. A lab that is nothing but measurements --
 * Memory, say, which reports sizes rather than yes/no capabilities -- gets
 * [Support.INFORMATIONAL] rather than a misleading tick.
 */
@HiltViewModel
class HardwareViewModel @Inject constructor(
    coordinator: ScanCoordinator,
) : ViewModel() {

    val state: StateFlow<HardwareUiState> = coordinator.state
        .map { scan ->
            when (scan) {
                is ScanState.Ready -> HardwareUiState(
                    loading = false,
                    labs = scan.profile.reports.map(::summarise),
                    totalFacts = scan.profile.allFacts().size,
                )

                is ScanState.Failed -> HardwareUiState(loading = false)
                else -> HardwareUiState(loading = true)
            }
        }
        .asUiState(viewModelScope, HardwareUiState())

    private fun summarise(report: LabReport): LabSummary {
        val facts = report.allFacts()
        val verdicts = facts.filter { it.support != Support.INFORMATIONAL }
        val affirmative = verdicts.count { it.support.isAffirmative }
        val support = when {
            verdicts.isEmpty() -> Support.INFORMATIONAL
            affirmative == verdicts.size -> Support.SUPPORTED
            affirmative > 0 -> Support.PARTIAL
            verdicts.all { it.support == Support.UNKNOWN } -> Support.UNKNOWN
            verdicts.any { it.support == Support.NOT_EXPOSED } -> Support.NOT_EXPOSED
            else -> Support.UNSUPPORTED
        }
        return LabSummary(
            lab = report.lab,
            support = support,
            facts = facts.size,
            affirmative = affirmative,
            verdicts = verdicts.size,
            hasNotes = report.notes.isNotEmpty(),
        )
    }
}

/**
 * One lab's report, optionally filtered.
 *
 * @param sections the section tree after filtering, empty when nothing matched
 * @param matched how many facts survived the query
 */
data class LabUiState(
    val loading: Boolean = true,
    val lab: Lab? = null,
    val sections: List<Section> = emptyList(),
    val notes: List<String> = emptyList(),
    val query: String = "",
    val matched: Int = 0,
    val total: Int = 0,
    val hidden: Int = 0,
)

/**
 * A single lab's detail screen.
 *
 * The lab is read from the navigation argument through [SavedStateHandle], so the screen is
 * reconstructible after process death without the index screen having to hand it over
 * again.
 *
 * Filtering uses [Section.filtered], which prunes the tree rather than flattening it: a
 * child section whose title matches keeps all of its facts, and one where only two facts
 * match keeps just those two under their real heading. Flattening would lose the structure
 * that makes a camera report readable, where the same labels repeat per camera.
 */
@HiltViewModel
class LabViewModel @Inject constructor(
    coordinator: ScanCoordinator,
    private val savedState: SavedStateHandle,
    settings: SettingsRepository,
) : ViewModel() {

    private val lab: Lab? = savedState.get<String>(Routes.ARG_LAB)?.let(Lab::fromId)

    private val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")

    private val showUnavailable = settings.settings.map { it.showUnavailable }

    val state: StateFlow<LabUiState> = combine(
        coordinator.state,
        query,
        showUnavailable,
    ) { scan, text, unavailable ->
        val report = if (lab == null) {
            null
        } else {
            (scan as? ScanState.Ready)?.profile?.report(lab)
        }
        when {
            // An unrecognised lab id in the route. Shows the screen's empty state rather
            // than a spinner that would never resolve.
            lab == null -> LabUiState(loading = false, lab = null, query = text)
            report != null -> project(report, text, unavailable)
            scan is ScanState.Failed -> LabUiState(loading = false, lab = lab, query = text)
            else -> loading(text)
        }
    }.asUiState(viewModelScope, LabUiState(lab = lab))

    fun onQueryChange(value: String) {
        savedState[KEY_QUERY] = value
    }

    private fun loading(text: String) = LabUiState(loading = true, lab = lab, query = text)

    private fun project(
        report: LabReport,
        text: String,
        showUnavailable: Boolean,
    ): LabUiState {
        val visible = if (showUnavailable) {
            report.sections
        } else {
            report.sections.mapNotNull { it.pruneUnavailable() }
        }
        val sections = if (text.isBlank()) {
            visible
        } else {
            visible.mapNotNull { it.filtered(text) }
        }
        val total = report.allFacts().size
        val shown = visible.sumOf { it.allFacts().size }
        return LabUiState(
            loading = false,
            lab = report.lab,
            sections = sections,
            notes = report.notes,
            query = text,
            matched = sections.sumOf { it.allFacts().size },
            total = total,
            hidden = total - shown,
        )
    }

    private companion object {
        const val KEY_QUERY = "lab.query"
    }
}

/**
 * Drops facts the platform would not answer.
 *
 * Only ever applied when the user has explicitly asked for it in Settings, and the count of
 * what was dropped is shown on the screen -- Section 8's rule that a device must not be made
 * to look more capable than it reported means hiding these rows cannot also hide the fact
 * that they were hidden.
 *
 * "Unavailable" here is the *support*, not the provenance: a fact that was queried
 * successfully and came back "no" stays, because that is an answer. What goes is the row
 * whose answer is that there is no answer.
 */
private fun Section.pruneUnavailable(): Section? {
    val keptFacts = facts.filter { it.isAvailable() }
    val keptChildren = children.mapNotNull { it.pruneUnavailable() }
    if (keptFacts.isEmpty() && keptChildren.isEmpty()) return null
    return copy(facts = keptFacts, children = keptChildren)
}

private fun Fact.isAvailable(): Boolean =
    support != Support.NOT_EXPOSED && support != Support.UNKNOWN
