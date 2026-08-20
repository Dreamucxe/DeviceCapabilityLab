package com.devicelab.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devicelab.data.export.ExportFormat
import com.devicelab.data.settings.Settings
import com.devicelab.data.settings.SettingsRepository
import com.devicelab.data.settings.ThemeMode
import com.devicelab.ui.asUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @param dynamicColorAvailable false below Android 12, where the platform has no wallpaper
 *   palette to read. The switch is shown but disabled, with its note explaining why, rather
 *   than hidden -- a missing switch looks like a missing feature in the app.
 */
data class SettingsUiState(
    val settings: Settings = Settings(),
    val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = repository.settings
        .map { SettingsUiState(settings = it) }
        .asUiState(viewModelScope, SettingsUiState())

    fun setTheme(mode: ThemeMode) = edit { repository.setTheme(mode) }

    fun setDynamicColor(enabled: Boolean) = edit { repository.setDynamicColor(enabled) }

    fun setReduceMotion(enabled: Boolean) = edit { repository.setReduceMotion(enabled) }

    fun setMonospaceValues(enabled: Boolean) = edit { repository.setMonospaceValues(enabled) }

    fun setShowProvenance(enabled: Boolean) = edit { repository.setShowProvenance(enabled) }

    fun setShowUnavailable(enabled: Boolean) = edit { repository.setShowUnavailable(enabled) }

    fun setExportFormat(format: ExportFormat) = edit { repository.setExportFormat(format) }

    fun setKeepSnapshots(count: Int) = edit { repository.setKeepSnapshots(count) }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
