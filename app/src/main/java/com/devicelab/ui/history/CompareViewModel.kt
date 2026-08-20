package com.devicelab.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devicelab.core.model.ChangeKind
import com.devicelab.core.model.FactDelta
import com.devicelab.core.model.Lab
import com.devicelab.core.model.SnapshotDiff
import com.devicelab.data.repo.ScanCoordinator
import com.devicelab.data.repo.ScanState
import com.devicelab.data.repo.SnapshotRepository
import com.devicelab.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A comparison of two snapshots.
 *
 * @param diff null while loading, and also when either side could not be loaded
 * @param unavailable set when the comparison cannot be produced at all -- a deleted
 *   snapshot, or a request to compare against a live scan that has not finished
 */
data class CompareUiState(
    val loading: Boolean = true,
    val diff: SnapshotDiff? = null,
    val unavailable: Boolean = false,
    val showUnchanged: Boolean = false,
    val groups: List<Pair<Lab, List<FactDelta>>> = emptyList(),
)

/**
 * The comparison screen.
 *
 * Loads once, in `init`, rather than reacting to a flow. A comparison is a snapshot of a
 * snapshot: both sides are immutable stored rows, so there is nothing to observe, and
 * recomputing a thousand-row diff because an unrelated flow emitted would be waste. The one
 * moving part is the live side, and if the scan is not ready when this screen opens the
 * screen says so rather than silently comparing against a partial profile.
 */
@HiltViewModel
class CompareViewModel @Inject constructor(
    private val snapshots: SnapshotRepository,
    private val coordinator: ScanCoordinator,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val leftId: Long? = savedState.get<String>(Routes.ARG_LEFT)?.toLongOrNull()
    private val rightArg: String? = savedState.get<String>(Routes.ARG_RIGHT)

    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() {
        if (_state.value.loading) return
        _state.value = CompareUiState(loading = true)
        load()
    }

    fun toggleUnchanged() {
        _state.update { current ->
            val show = !current.showUnchanged
            current.copy(showUnchanged = show, groups = group(current.diff, show))
        }
    }

    private fun load() {
        viewModelScope.launch {
            val diff = runCatching { compute() }.getOrNull()
            _state.value = CompareUiState(
                loading = false,
                diff = diff,
                unavailable = diff == null,
                showUnchanged = false,
                groups = group(diff, false),
            )
        }
    }

    private suspend fun compute(): SnapshotDiff? {
        val left = leftId ?: return null
        if (rightArg == Routes.LIVE) {
            val ready = coordinator.state.value as? ScanState.Ready ?: return null
            return snapshots.compareWithLive(left, ready.profile, coordinator.identity)
        }
        val right = rightArg?.toLongOrNull() ?: return null
        return snapshots.compare(left, right)
    }

    /**
     * Groups the deltas by lab for display.
     *
     * Unchanged rows are excluded by default. On two scans of the same device on the same
     * build, they are the overwhelming majority -- often over 95% -- and leading with a
     * thousand identical rows would bury the handful that differ. Section 19 asks for all
     * four kinds to be distinguished, not for all four to be equally prominent, and the
     * unchanged count is shown as a figure whether or not the rows are expanded.
     */
    private fun group(diff: SnapshotDiff?, showUnchanged: Boolean): List<Pair<Lab, List<FactDelta>>> {
        if (diff == null) return emptyList()
        val kinds = buildSet {
            add(ChangeKind.ADDED)
            add(ChangeKind.REMOVED)
            add(ChangeKind.CHANGED)
            if (showUnchanged) add(ChangeKind.UNCHANGED)
        }
        return diff.byLab(kinds)
    }
}
