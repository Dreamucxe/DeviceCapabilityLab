package com.devicelab.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devicelab.core.model.DomainStatus
import com.devicelab.core.model.FactRow
import com.devicelab.core.model.Lab
import com.devicelab.core.model.Snapshot
import com.devicelab.data.repo.SnapshotRepository
import com.devicelab.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One section's worth of stored rows, under the heading they were read beneath. */
data class StoredGroup(
    val lab: Lab,
    val sectionTitle: String,
    val rows: List<FactRow>,
)

/**
 * A stored snapshot, opened for reading.
 *
 * @param snapshot null while loading, and when the id no longer exists
 * @param missing set once loading has finished and found nothing
 */
data class SnapshotUiState(
    val loading: Boolean = true,
    val snapshot: Snapshot? = null,
    val missing: Boolean = false,
    val query: String = "",
    val groups: List<StoredGroup> = emptyList(),
    val scorecard: List<DomainStatus> = emptyList(),
    val matched: Int = 0,
    val total: Int = 0,
)

/**
 * The stored-snapshot viewer.
 *
 * Nothing here is re-read from the device. A snapshot may have been taken on a different
 * device or a different Android version, and the whole value of history is that it says what
 * *was* true; consulting `Build` or a live detector at display time would quietly relabel an
 * old scan with today's hardware.
 *
 * Loaded once in `init` rather than observed: the stored rows are immutable. Renaming from
 * the history list changes a name this screen does not show as the source of truth, and a
 * deletion is handled by the missing state on the next open.
 */
@HiltViewModel
class SnapshotViewModel @Inject constructor(
    private val snapshots: SnapshotRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val id: Long? = savedState.get<String>(Routes.ARG_SNAPSHOT)?.toLongOrNull()

    private val _state = MutableStateFlow(SnapshotUiState())
    val state: StateFlow<SnapshotUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = id?.let { runCatching { snapshots.load(it) }.getOrNull() }
            if (loaded == null) {
                _state.value = SnapshotUiState(loading = false, missing = true)
            } else {
                _state.value = project(loaded, "")
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { current ->
            val snapshot = current.snapshot ?: return@update current.copy(query = value)
            project(snapshot, value)
        }
    }

    /**
     * Groups the rows for display.
     *
     * Grouping is by section *path* but headed by section title, so two cameras that both
     * produced a section called "Capabilities" stay apart -- the path is what makes them
     * distinct, and collapsing on the title would merge one camera's answers into another's.
     */
    private fun project(snapshot: Snapshot, query: String): SnapshotUiState {
        val visible = if (query.isBlank()) {
            snapshot.rows
        } else {
            snapshot.rows.filter { it.matches(query) }
        }
        val groups = ArrayList<StoredGroup>()
        var currentKey: Pair<String, String>? = null
        var bucket = ArrayList<FactRow>()
        visible.forEach { row ->
            val key = row.labId to row.sectionPath
            if (key != currentKey) {
                flush(currentKey, bucket, groups)
                currentKey = key
                bucket = ArrayList()
            }
            bucket += row
        }
        flush(currentKey, bucket, groups)
        return SnapshotUiState(
            loading = false,
            snapshot = snapshot,
            missing = false,
            query = query,
            groups = groups,
            scorecard = snapshot.scorecard,
            matched = visible.size,
            total = snapshot.rows.size,
        )
    }

    private fun flush(
        key: Pair<String, String>?,
        bucket: List<FactRow>,
        out: MutableList<StoredGroup>,
    ) {
        if (key == null || bucket.isEmpty()) return
        val lab = Lab.fromId(key.first) ?: return
        out += StoredGroup(
            lab = lab,
            sectionTitle = bucket.first().sectionTitle,
            rows = bucket,
        )
    }
}
