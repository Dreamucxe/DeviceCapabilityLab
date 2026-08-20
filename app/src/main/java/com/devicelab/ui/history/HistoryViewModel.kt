package com.devicelab.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devicelab.core.model.SnapshotSummary
import com.devicelab.data.repo.ScanCoordinator
import com.devicelab.data.repo.ScanState
import com.devicelab.data.repo.SnapshotRepository
import com.devicelab.ui.asUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The history screen.
 *
 * @param selected ids picked for comparison, at most two, oldest-picked dropped first
 * @param renaming the snapshot whose name is being edited, or null
 * @param canCompareWithLive whether a live scan exists to compare against
 */
data class HistoryUiState(
    val loading: Boolean = true,
    val snapshots: List<SnapshotSummary> = emptyList(),
    val selected: List<Long> = emptyList(),
    val renaming: SnapshotSummary? = null,
    val confirmingDeleteAll: Boolean = false,
    val canCompareWithLive: Boolean = false,
) {
    val comparablePair: Pair<Long, Long>?
        get() = if (selected.size == 2) selected[0] to selected[1] else null
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val snapshots: SnapshotRepository,
    coordinator: ScanCoordinator,
) : ViewModel() {

    private val selection = MutableStateFlow<List<Long>>(emptyList())
    private val renaming = MutableStateFlow<SnapshotSummary?>(null)
    private val confirmingDeleteAll = MutableStateFlow(false)

    val state: StateFlow<HistoryUiState> = combine(
        snapshots.observeSummaries(),
        selection,
        renaming,
        confirmingDeleteAll,
        coordinator.state.map { it is ScanState.Ready },
    ) { list, selected, rename, confirming, live ->
        // A snapshot deleted from under a selection must not leave a dangling id that would
        // open a comparison against nothing.
        val present = list.map { it.id }.toSet()
        HistoryUiState(
            loading = false,
            snapshots = list,
            selected = selected.filter { it in present },
            renaming = rename?.let { current -> list.firstOrNull { it.id == current.id } },
            confirmingDeleteAll = confirming,
            canCompareWithLive = live,
        )
    }.asUiState(viewModelScope, HistoryUiState())

    /**
     * Adds or removes a snapshot from the comparison selection.
     *
     * Comparison is always between exactly two, so selecting a third drops the first. That
     * is less surprising than refusing the tap: the user's intent in tapping a third is
     * clearly to compare it, and which of the earlier two they meant to keep is answered by
     * the one they picked most recently.
     */
    fun toggleSelection(id: Long) {
        selection.update { current ->
            when {
                id in current -> current - id
                current.size < 2 -> current + id
                else -> listOf(current.last(), id)
            }
        }
    }

    fun clearSelection() {
        selection.value = emptyList()
    }

    fun startRename(summary: SnapshotSummary) {
        renaming.value = summary
    }

    fun cancelRename() {
        renaming.value = null
    }

    fun confirmRename(name: String) {
        val target = renaming.value ?: return
        renaming.value = null
        if (name.isBlank() || name == target.name) return
        viewModelScope.launch { snapshots.rename(target.id, name) }
    }

    fun delete(id: Long) {
        selection.update { it - id }
        viewModelScope.launch { snapshots.delete(id) }
    }

    fun askDeleteAll() {
        confirmingDeleteAll.value = true
    }

    fun cancelDeleteAll() {
        confirmingDeleteAll.value = false
    }

    fun confirmDeleteAll() {
        confirmingDeleteAll.value = false
        selection.value = emptyList()
        viewModelScope.launch { snapshots.deleteAll() }
    }
}
