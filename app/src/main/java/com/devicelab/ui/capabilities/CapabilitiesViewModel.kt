package com.devicelab.ui.capabilities

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devicelab.core.model.Availability
import com.devicelab.core.model.CapabilityMatrix
import com.devicelab.core.model.Lab
import com.devicelab.core.model.MatrixRow
import com.devicelab.data.repo.ScanCoordinator
import com.devicelab.data.repo.ScanState
import com.devicelab.data.settings.SettingsRepository
import com.devicelab.ui.asUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The one filter the matrix offers, beyond the search box. */
enum class MatrixFilter {
    ALL,
    SUPPORTED,

    /** Rows the platform would answer but this Android version does not expose. */
    API_LEVEL,

    /** Rows the platform answered with "this hardware does not have it". */
    HARDWARE,
    ;
}

/**
 * The matrix, filtered.
 *
 * @param groups rows grouped by lab, in report order, with empty groups dropped
 * @param shown how many rows survived the filter and the query
 * @param total how many rows the matrix holds in total
 */
data class MatrixUiState(
    val loading: Boolean = true,
    val query: String = "",
    val filter: MatrixFilter = MatrixFilter.ALL,
    val groups: List<Pair<Lab, List<MatrixRow>>> = emptyList(),
    val shown: Int = 0,
    val total: Int = 0,
    val supported: Int = 0,
    val apiLevelGated: Int = 0,
    val hardwareAbsent: Int = 0,
) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * The capability matrix screen.
 *
 * The query and the filter live in [SavedStateHandle] rather than in a plain
 * `MutableStateFlow`, so a search survives the process being killed in the background --
 * which for this screen is the difference between coming back to your search and coming
 * back to a thousand unfiltered rows.
 *
 * Filtering runs on every keystroke against an in-memory list. That is deliberate and it is
 * what Section 21 asks for; the matrix for a well-equipped device is on the order of a
 * thousand rows, and a substring scan over that is well inside a frame.
 */
@HiltViewModel
class CapabilitiesViewModel @Inject constructor(
    private val coordinator: ScanCoordinator,
    private val savedState: SavedStateHandle,
    settings: SettingsRepository,
) : ViewModel() {

    private val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")
    private val filter = MutableStateFlow(
        MatrixFilter.entries.firstOrNull { it.name == savedState.get<String>(KEY_FILTER) }
            ?: MatrixFilter.ALL
    )

    private val showUnavailable = settings.settings.map { it.showUnavailable }

    val state: StateFlow<MatrixUiState> = combine(
        coordinator.state,
        query,
        filter,
        showUnavailable,
    ) { scan, text, active, unavailable ->
        when (scan) {
            is ScanState.Ready -> project(scan.matrix, text, active, unavailable)
            is ScanState.Failed -> MatrixUiState(loading = false)
            else -> MatrixUiState(loading = true, query = text, filter = active)
        }
    }.asUiState(viewModelScope, MatrixUiState())

    fun onQueryChange(value: String) {
        savedState[KEY_QUERY] = value
    }

    fun onFilterChange(value: MatrixFilter) {
        savedState[KEY_FILTER] = value.name
        filter.value = value
    }

    /**
     * Applies the filter, then the query.
     *
     * The counts on the filter chips are computed from the *unfiltered* matrix, so they do
     * not change as you type. A chip labelled "Hardware 12" that reads "Hardware 0" the
     * moment you search for something else would be telling you about your query rather
     * than about your device.
     */
    private fun project(
        matrix: CapabilityMatrix,
        text: String,
        active: MatrixFilter,
        showUnavailable: Boolean,
    ): MatrixUiState {
        val searched = matrix.filtered(text)
        val visible = searched.rows.filter { row ->
            val passesFilter = when (active) {
                MatrixFilter.ALL -> true
                MatrixFilter.SUPPORTED -> row.support.isAffirmative
                MatrixFilter.API_LEVEL -> row.availability == Availability.API_LEVEL
                MatrixFilter.HARDWARE -> row.availability == Availability.HARDWARE
            }
            val passesVisibility = showUnavailable ||
                active != MatrixFilter.ALL ||
                row.availability == Availability.AVAILABLE
            passesFilter && passesVisibility
        }
        val groups = Lab.entries.mapNotNull { lab ->
            val rows = visible.filter { it.lab == lab }
            if (rows.isEmpty()) null else lab to rows
        }
        return MatrixUiState(
            loading = false,
            query = text,
            filter = active,
            groups = groups,
            shown = visible.size,
            total = matrix.rows.size,
            supported = matrix.rows.count { it.support.isAffirmative },
            apiLevelGated = matrix.count(Availability.API_LEVEL),
            hardwareAbsent = matrix.count(Availability.HARDWARE),
        )
    }

    private companion object {
        const val KEY_QUERY = "matrix.query"
        const val KEY_FILTER = "matrix.filter"
    }
}
