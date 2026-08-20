package com.devicelab.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.devicelab.data.export.ExportFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How the app picks its colours. */
enum class ThemeMode(val id: String, val label: String) {
    DARK("dark", "Dark"),
    LIGHT("light", "Light"),
    SYSTEM("system", "Follow system"),
    ;

    companion object {
        /** Dark by default, as Section 22 requires. */
        val DEFAULT = DARK

        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * The user's settings.
 *
 * Every one of these changes how the app behaves, not what the device reports. There
 * is no setting that unlocks additional data, because there is no additional data to
 * unlock -- what the platform will return does not depend on a preference.
 *
 * @param reduceMotion honours Section 27's reduced-motion requirement as an explicit
 *   override on top of the system animation scale
 * @param showProvenance whether every row shows which API answered it. On by default:
 *   the provenance line is the reason a reader can check a claim rather than take it
 *   on trust, which is the whole premise of the app.
 * @param keepSnapshots how many saved scans to retain, oldest deleted first
 */
data class Settings(
    val theme: ThemeMode = ThemeMode.DEFAULT,
    val dynamicColor: Boolean = true,
    val reduceMotion: Boolean = false,
    val showProvenance: Boolean = true,
    val showUnavailable: Boolean = true,
    val monospaceValues: Boolean = true,
    val exportFormat: ExportFormat = ExportFormat.JSON,
    val keepSnapshots: Int = DEFAULT_KEEP,
) {
    companion object {
        const val DEFAULT_KEEP = 20
        val KEEP_CHOICES = listOf(5, 10, 20, 50, 100)
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "device-lab-settings"
)

/**
 * Reads and writes [Settings] through DataStore.
 *
 * The [catch] on the flow returns defaults on [IOException] rather than propagating.
 * A corrupt or unreadable preferences file is not a reason to refuse to start; the app
 * has a complete set of working defaults and losing a theme choice is a far smaller
 * failure than a blank screen.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val settings: Flow<Settings> = context.settingsStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs ->
            Settings(
                theme = ThemeMode.fromId(prefs[KEY_THEME]),
                dynamicColor = prefs[KEY_DYNAMIC] ?: true,
                reduceMotion = prefs[KEY_REDUCE_MOTION] ?: false,
                showProvenance = prefs[KEY_PROVENANCE] ?: true,
                showUnavailable = prefs[KEY_SHOW_UNAVAILABLE] ?: true,
                monospaceValues = prefs[KEY_MONOSPACE] ?: true,
                exportFormat = ExportFormat.entries
                    .firstOrNull { it.id == prefs[KEY_EXPORT_FORMAT] } ?: ExportFormat.JSON,
                keepSnapshots = prefs[KEY_KEEP] ?: Settings.DEFAULT_KEEP,
            )
        }

    suspend fun setTheme(mode: ThemeMode) = put { it[KEY_THEME] = mode.id }

    suspend fun setDynamicColor(enabled: Boolean) = put { it[KEY_DYNAMIC] = enabled }

    suspend fun setReduceMotion(enabled: Boolean) = put { it[KEY_REDUCE_MOTION] = enabled }

    suspend fun setShowProvenance(enabled: Boolean) = put { it[KEY_PROVENANCE] = enabled }

    suspend fun setShowUnavailable(enabled: Boolean) = put { it[KEY_SHOW_UNAVAILABLE] = enabled }

    suspend fun setMonospaceValues(enabled: Boolean) = put { it[KEY_MONOSPACE] = enabled }

    suspend fun setExportFormat(format: ExportFormat) = put { it[KEY_EXPORT_FORMAT] = format.id }

    suspend fun setKeepSnapshots(count: Int) = put { it[KEY_KEEP] = count }

    private suspend fun put(block: (MutablePreferences) -> Unit) {
        try {
            context.settingsStore.edit(block)
        } catch (_: IOException) {
            // A failed write means the preference does not persist. Everything the
            // user is looking at is already updated from the in-memory flow, so
            // surfacing this as an error would be noise about a retryable action.
        }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_DYNAMIC = booleanPreferencesKey("dynamic_color")
        val KEY_REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val KEY_PROVENANCE = booleanPreferencesKey("show_provenance")
        val KEY_SHOW_UNAVAILABLE = booleanPreferencesKey("show_unavailable")
        val KEY_MONOSPACE = booleanPreferencesKey("monospace_values")
        val KEY_EXPORT_FORMAT = stringPreferencesKey("export_format")
        val KEY_KEEP = intPreferencesKey("keep_snapshots")
    }
}
