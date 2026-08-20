package com.devicelab.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devicelab.core.model.DeviceIdentity
import com.devicelab.data.repo.ScanCoordinator
import com.devicelab.data.repo.ScanState
import com.devicelab.data.repo.SnapshotRepository
import com.devicelab.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the dashboard needs beyond the scan itself.
 *
 * @param saving true while a snapshot is being written
 * @param justSaved set after a successful save so the confirmation can be shown, then cleared
 * @param error a message for a failure the user should see, cleared when acknowledged
 */
data class DashboardUiState(
    val saving: Boolean = false,
    val justSaved: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val coordinator: ScanCoordinator,
    private val snapshots: SnapshotRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val scanState: StateFlow<ScanState> = coordinator.state

    val snapshotCount: StateFlow<Int> = snapshots.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _ui = MutableStateFlow(DashboardUiState())
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()

    val identity: DeviceIdentity get() = coordinator.identity

    fun rescan() {
        _ui.update { it.copy(justSaved = false, error = null) }
        coordinator.rescan()
    }

    /**
     * Saves the scan currently on screen.
     *
     * The retention limit is read at save time rather than held in state, so lowering it in
     * Settings takes effect on the next save without the two screens having to coordinate.
     */
    fun saveSnapshot() {
        val profile = (scanState.value as? ScanState.Ready)?.profile ?: return
        if (_ui.value.saving) return
        _ui.update { it.copy(saving = true, error = null, justSaved = false) }
        viewModelScope.launch {
            runCatching {
                val keep = settings.settings.first().keepSnapshots
                snapshots.saveScan(
                    profile = profile,
                    identity = coordinator.identity,
                    keepAtMost = keep,
                )
            }.onSuccess {
                _ui.update { it.copy(saving = false, justSaved = true) }
            }.onFailure { cause ->
                _ui.update {
                    it.copy(
                        saving = false,
                        error = cause.message ?: cause::class.java.simpleName,
                    )
                }
            }
        }
    }

    fun acknowledge() {
        _ui.update { it.copy(justSaved = false, error = null) }
    }
}
