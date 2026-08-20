package com.devicelab.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.devicelab.core.model.CapabilityProfile
import com.devicelab.core.model.DeviceIdentity
import com.devicelab.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A written export file and the intent that shares it. */
data class ExportResult(
    val document: ExportDocument,
    val file: File,
    val shareIntent: Intent,
)

/**
 * Writes an export to a file the user can share, and shares it.
 *
 * Files go to `cacheDir/exports` and are exposed through a [FileProvider], so no
 * storage permission is needed on any supported API level. Section 25 asks for only
 * genuinely required permissions, and WRITE_EXTERNAL_STORAGE would be neither required
 * nor granted on a modern device.
 *
 * The directory is cleared before each write. An export is a transient artefact -- once
 * shared it lives wherever the user sent it -- and leaving old reports in the cache
 * would accumulate a growing record of the device without the user having asked for
 * one.
 */
@Singleton
class ExportWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: ReportExporter,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun write(
        profile: CapabilityProfile,
        identity: DeviceIdentity,
        format: ExportFormat,
        appVersion: String,
    ): ExportResult = withContext(io) {
        val document = exporter.render(profile, identity, format, appVersion)
        val dir = File(context.cacheDir, DIRECTORY).apply {
            if (!exists()) mkdirs() else listFiles()?.forEach { it.delete() }
        }
        val file = File(dir, document.filename)
        file.writeText(document.content, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${identity.manufacturer} ${identity.model}".trim())
            // Grants the receiving app read access to this one URI for the life of the
            // activity it starts. Nothing else in the cache is reachable through it.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ExportResult(document, file, Intent.createChooser(intent, "Share capability report"))
    }

    /** The rendered text, for the preview and for copy-to-clipboard. */
    suspend fun preview(
        profile: CapabilityProfile,
        identity: DeviceIdentity,
        format: ExportFormat,
        appVersion: String,
    ): ExportDocument = withContext(io) {
        exporter.render(profile, identity, format, appVersion)
    }

    private companion object {
        const val DIRECTORY = "exports"
    }
}
