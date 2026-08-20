package com.devicelab.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devicelab.data.repo.ScanCoordinator
import com.devicelab.data.settings.Settings
import com.devicelab.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Settings for the theme, and the one place the scan is started.
 *
 * Scoped to the activity, so [ScanCoordinator.ensureScanned] runs once per process rather
 * than once per screen. Starting it here rather than in [com.devicelab.DeviceLabApplication]
 * is deliberate: `Application.onCreate` runs before the window exists and contributes
 * directly to cold-start time, and a scan that touches the camera and DRM services is not
 * something to do while the system is still waiting to draw the first frame.
 *
 * [SharingStarted.Eagerly] rather than `WhileSubscribed`, because the collector is the theme
 * wrapper around the whole UI: letting it lapse and restart would flash default colours
 * during a configuration change.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val coordinator: ScanCoordinator,
) : ViewModel() {

    private val _settingsLoaded = MutableStateFlow(false)

    /**
     * Whether the stored settings have arrived, as opposed to the defaults [settings] starts
     * from.
     *
     * [MainActivity] holds the splash screen until this is true, so a user who chose the light
     * theme never sees a dark frame first. It flips on the first emission whatever that
     * emission contains -- and the repository turns an unreadable preferences file into a
     * defaults emission rather than an error -- so no input leaves the splash up.
     */
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    /**
     * The user's presentation settings.
     *
     * The flag is set from an [onEach] on the repository flow rather than by awaiting the
     * first value of this [StateFlow]: a `StateFlow` always has a value, so awaiting it would
     * hand back the defaults immediately and the flag would mean nothing. Doing it here also
     * keeps DataStore to a single collection instead of one for the theme and one for the flag.
     */
    val settings: StateFlow<Settings> = settingsRepository.settings
        .onEach { _settingsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    init {
        coordinator.ensureScanned()
    }
}
