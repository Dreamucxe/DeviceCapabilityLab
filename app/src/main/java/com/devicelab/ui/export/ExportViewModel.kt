package com.devicelab.ui.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devicelab.BuildConfig
import com.devicelab.core.model.CapabilityProfile
import com.devicelab.data.export.ExportDocument
import com.devicelab.data.export.ExportFormat
import com.devicelab.data.export.ExportWriter
import com.devicelab.data.repo.ScanCoordinator
import com.devicelab.data.repo.ScanState
import com.devicelab.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @param document the rendered report, kept so the size and value count can be shown before
 *   the user commits to sharing
 * @param values how many facts the report contains, shown next to the size
 * @param shareIntent a chooser for the written file, consumed once then cleared
 * @param copied set after a successful clipboard write, cleared when acknowledged
 */
data class ExportUiState(
    val format: ExportFormat = ExportFormat.JSON,
    val rendering: Boolean = false,
    val document: ExportDocument? = null,
    val values: Int = 0,
    val shareIntent: Intent? = null,
    val copied: Boolean = false,
    val error: String? = null,
    val ready: Boolean = false,
)

/**
 * The export sheet.
 *
 * Rendering happens off the main thread inside [ExportWriter] and produces the whole
 * document in memory before anything is written, so a failure -- a full cache directory, a
 * revoked provider grant -- surfaces as a message rather than as a truncated file the user
 * then shares.
 *
 * The clipboard path deliberately does not offer HTML: pasting a full HTML document into a
 * message is not useful, and the plain-text renderer exists precisely for that case.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val writer: ExportWriter,
    private val coordinator: ScanCoordinator,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val preferred = settings.settings.first().exportFormat
            _state.update { it.copy(format = preferred) }
            render(preferred)
        }
    }

    fun selectFormat(format: ExportFormat) {
        if (format == _state.value.format && _state.value.document != null) return
        _state.update { it.copy(format = format, document = null, error = null) }
        render(format)
    }

    /** Writes the file and produces a chooser. */
    fun share() {
        val profile = readyProfile() ?: return
        _state.update { it.copy(rendering = true, error = null) }
        viewModelScope.launch {
            runCatching {
                writer.write(
                    profile = profile,
                    identity = coordinator.identity,
                    format = _state.value.format,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        rendering = false,
                        document = result.document,
                        values = profile.allFacts().size,
                        shareIntent = result.shareIntent,
                        ready = true,
                    )
                }
            }.onFailure { cause -> fail(cause) }
        }
    }

    /** Marks the chooser as launched so a recomposition does not launch it twice. */
    fun shareLaunched() {
        _state.update { it.copy(shareIntent = null) }
    }

    fun copy() {
        val document = _state.value.document ?: return
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: error("Clipboard unavailable")
            clipboard.setPrimaryClip(
                ClipData.newPlainText(document.filename, document.content)
            )
        }.onSuccess {
            _state.update { it.copy(copied = true, error = null) }
        }.onFailure { cause -> fail(cause) }
    }

    fun acknowledge() {
        _state.update { it.copy(copied = false, error = null) }
    }

    private fun render(format: ExportFormat) {
        val profile = readyProfile() ?: return
        _state.update { it.copy(rendering = true) }
        viewModelScope.launch {
            runCatching {
                writer.preview(
                    profile = profile,
                    identity = coordinator.identity,
                    format = format,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            }.onSuccess { document ->
                _state.update {
                    it.copy(
                        rendering = false,
                        document = document,
                        values = profile.allFacts().size,
                        ready = true,
                        error = null,
                    )
                }
            }.onFailure { cause -> fail(cause) }
        }
    }

    private fun readyProfile(): CapabilityProfile? =
        (coordinator.state.value as? ScanState.Ready)?.profile

    private fun fail(cause: Throwable) {
        _state.update {
            it.copy(
                rendering = false,
                error = cause.message ?: cause::class.java.simpleName,
            )
        }
    }
}
