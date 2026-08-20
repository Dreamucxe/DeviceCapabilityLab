package com.devicelab.data.repo

import com.devicelab.core.model.CapabilityMatrix
import com.devicelab.core.model.CapabilityProfile
import com.devicelab.core.model.DeviceIdentity
import com.devicelab.core.model.Lab
import com.devicelab.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** What the app knows about the device right now. */
sealed interface ScanState {

    data object Idle : ScanState

    /**
     * A scan in flight.
     *
     * [completed] and [total] are lab counts, not a percentage of work: labs differ
     * wildly in cost -- the codec lab enumerates every encoder and decoder on the
     * device, the memory lab reads four numbers -- so the bar advances unevenly. That is
     * honest about what is happening, where a smoothly interpolated bar would not be.
     */
    data class Scanning(
        val completed: Int,
        val total: Int,
        val current: Lab?,
    ) : ScanState {
        val fraction: Float get() = if (total <= 0) 0f else completed.toFloat() / total
    }

    data class Ready(
        val profile: CapabilityProfile,
        val matrix: CapabilityMatrix,
        val durationMillis: Long,
    ) : ScanState

    /** The scan itself failed, as opposed to an individual lab failing. */
    data class Failed(val reason: String) : ScanState
}

/**
 * The one scan every screen reads from.
 *
 * Application-scoped rather than owned by a ViewModel, because all five tabs show
 * facets of a single scan and the scan is expensive: opening the camera service,
 * instantiating DRM plugins and enumerating every codec is not something to redo when
 * the user moves from Dashboard to Capabilities. A ViewModel per screen would either
 * duplicate that work or need the profile threaded through navigation arguments.
 *
 * A [Mutex] guards starting a scan so that two screens arriving at once -- which is
 * exactly what happens on a cold start with a restored back stack -- produce one scan
 * rather than two racing ones. The second caller finds a scan already running and
 * simply observes it.
 */
@Singleton
class ScanCoordinator @Inject constructor(
    private val repository: CapabilityRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var job: Job? = null

    val identity: DeviceIdentity get() = repository.identity

    /** The profile if a scan has finished, otherwise null. */
    val profile: CapabilityProfile?
        get() = (_state.value as? ScanState.Ready)?.profile

    /** Starts a scan unless one has already run or is running. */
    fun ensureScanned() {
        if (_state.value is ScanState.Ready || _state.value is ScanState.Scanning) return
        start()
    }

    /** Discards the current result and scans again. */
    fun rescan() {
        job?.cancel()
        job = null
        start(force = true)
    }

    private fun start(force: Boolean = false) {
        job = scope.launch {
            mutex.withLock {
                // Re-checked under the lock: two screens can both pass the check in
                // ensureScanned() before either reaches here.
                if (!force && _state.value is ScanState.Ready) return@withLock
                _state.value = ScanState.Scanning(0, Lab.entries.size, null)
                try {
                    val profile = if (force) {
                        repository.refresh(::onProgress)
                    } else {
                        repository.profile(::onProgress)
                    }
                    // The Done progress callback already published Ready with a real
                    // duration. This covers the cached path, where no scan ran and so no
                    // duration was measured -- reporting zero would look like an
                    // impossibly fast scan.
                    if (_state.value !is ScanState.Ready) {
                        _state.value = ScanState.Ready(
                            profile = profile,
                            matrix = CapabilityMatrix.of(profile),
                            durationMillis = 0,
                        )
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    _state.value = ScanState.Failed(
                        t.javaClass.simpleName + (t.message?.let { ": ${it.take(200)}" } ?: "")
                    )
                }
            }
        }
    }

    private fun onProgress(progress: ScanProgress) {
        when (progress) {
            is ScanProgress.Running ->
                _state.value = ScanState.Scanning(progress.completed, progress.total, progress.current)
            is ScanProgress.Done ->
                _state.value = ScanState.Ready(
                    profile = progress.profile,
                    matrix = CapabilityMatrix.of(progress.profile),
                    durationMillis = progress.durationMillis,
                )
            ScanProgress.Idle -> Unit
        }
    }
}
