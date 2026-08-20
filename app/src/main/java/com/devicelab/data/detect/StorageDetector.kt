package com.devicelab.data.detect

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.devicelab.core.common.Format
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Storage volumes and capacity -- capability information only, never file contents.
 *
 * Two figures could each be called "total storage" and they differ by tens of
 * gigabytes, so both are shown and labelled:
 *
 *  * `StatFs` on the data partition -- what the *user* can fill.
 *  * `StorageStatsManager.getTotalBytes()` (API 26+) -- the nominal size of the
 *    whole volume, closer to the advertised capacity, because it counts the system
 *    partitions and reserved blocks too.
 *
 * No storage permission is requested or needed: every call here reports sizes, not
 * contents. Removable volumes are enumerated through [StorageManager], which gives
 * their state and description without any access to what is on them.
 */
class StorageDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.STORAGE

    override suspend fun detect(): LabReport {
        val sm = context.getSystemService(StorageManager::class.java)
        return LabReport(
            lab = lab,
            sections = listOfNotNull(
                internalStorage(),
                nominalCapacity(sm),
                externalStorage(),
                volumes(sm),
                filesystem(),
            ),
        )
    }

    private fun internalStorage(): Section {
        val dataDir = Environment.getDataDirectory()
        val stat = probe.attempt<StatFs?>(null) { StatFs(dataDir.absolutePath) }
        return Section(
            id = "internal",
            title = "Internal storage (user data)",
            subtitle = "StatFs on ${dataDir.absolutePath}",
            facts = listOf(
                probe.value(
                    "Usable capacity",
                    "StatFs.getBlockCountLong()",
                    minApi = 18,
                    searchTerms = listOf("storage", "capacity", "disk"),
                    detail = "The size of the data partition as the filesystem sees it.",
                ) { stat?.let { Format.bytes(it.blockCountLong * it.blockSizeLong) } },
                probe.value(
                    "Available to this app",
                    "StatFs.getAvailableBlocksLong()",
                    minApi = 18,
                    detail = "Free space an ordinary app may use. Smaller than the free " +
                        "figure the system shows itself, which may draw on reserved blocks.",
                ) { stat?.let { Format.bytes(it.availableBlocksLong * it.blockSizeLong) } },
                probe.value("Free (including reserved)", "StatFs.getFreeBlocksLong()", minApi = 18) {
                    stat?.let { Format.bytes(it.freeBlocksLong * it.blockSizeLong) }
                },
                probe.value("Used", "blockCount − freeBlocks", minApi = 18) {
                    stat?.let {
                        val total = it.blockCountLong * it.blockSizeLong
                        val free = it.freeBlocksLong * it.blockSizeLong
                        val used = (total - free).coerceAtLeast(0)
                        "${Format.bytes(used)} (${Format.percent(
                            if (total > 0) used.toDouble() / total else 0.0,
                        )})"
                    }
                },
                probe.value("Block size", "StatFs.getBlockSizeLong()", minApi = 18) {
                    "${stat?.blockSizeLong ?: return@value null} bytes"
                },
            ),
        )
    }

    private fun nominalCapacity(sm: StorageManager?) = Section(
        id = "nominal",
        title = "Nominal volume size",
        subtitle = "StorageStatsManager",
        facts = listOf(
            probe.value(
                "Total volume size",
                "StorageStatsManager.getTotalBytes()",
                minApi = 26,
                searchTerms = listOf("total storage", "advertised capacity", "128gb", "256gb"),
                detail = "The whole volume including system partitions and reserved " +
                    "blocks, which is why this is larger than the usable figure above " +
                    "and closer to the capacity on the box.",
            ) {
                val stats = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
                    ?: return@value null
                val uuid = StorageManager.UUID_DEFAULT
                Format.bytes(stats.getTotalBytes(uuid))
            },
            probe.value(
                "Free volume space",
                "StorageStatsManager.getFreeBytes()",
                minApi = 26,
            ) {
                val stats = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
                    ?: return@value null
                Format.bytes(stats.getFreeBytes(StorageManager.UUID_DEFAULT))
            },
            probe.value(
                "Allocatable to this app",
                "StorageManager.getAllocatableBytes()",
                minApi = 26,
                detail = "How much this app could obtain, counting space the platform " +
                    "would clear from caches on request.",
            ) {
                sm?.let { Format.bytes(it.getAllocatableBytes(StorageManager.UUID_DEFAULT)) }
            },
            probe.value(
                "Cache quota for this app",
                "StorageManager.getCacheQuotaBytes()",
                minApi = 26,
            ) { sm?.let { Format.bytes(it.getCacheQuotaBytes(StorageManager.UUID_DEFAULT)) } },
        ),
    )

    private fun externalStorage(): Section {
        val state = probe.attempt(Environment.MEDIA_UNKNOWN) { Environment.getExternalStorageState() }
        val external = probe.attempt<File?>(null) { Environment.getExternalStorageDirectory() }
        val stat = external?.let { dir ->
            probe.attempt<StatFs?>(null) { if (dir.exists()) StatFs(dir.absolutePath) else null }
        }
        return Section(
            id = "external",
            title = "External / shared storage",
            subtitle = "Environment",
            facts = listOf(
                probe.value("State", "Environment.getExternalStorageState()") {
                    Format.titleCaseEnum(state.uppercase())
                },
                probe.flag(
                    "Emulated",
                    "Environment.isExternalStorageEmulated()",
                    supportedText = "Yes — backed by internal storage",
                    unsupportedText = "No — physically separate media",
                ) { Environment.isExternalStorageEmulated() },
                probe.flag(
                    "Removable",
                    "Environment.isExternalStorageRemovable()",
                ) { Environment.isExternalStorageRemovable() },
                probe.flag(
                    "Legacy storage view",
                    "Environment.isExternalStorageLegacy()",
                    minApi = 29,
                    detail = "Whether this app sees the pre-scoped-storage filesystem view.",
                ) { Environment.isExternalStorageLegacy() },
                probe.value("Shared capacity", "StatFs on external storage", minApi = 18) {
                    stat?.let { Format.bytes(it.blockCountLong * it.blockSizeLong) }
                },
                probe.value("Shared available", "StatFs on external storage", minApi = 18) {
                    stat?.let { Format.bytes(it.availableBlocksLong * it.blockSizeLong) }
                },
            ),
        )
    }

    private fun volumes(sm: StorageManager?): Section {
        val volumes: List<StorageVolume> = probe.attempt(emptyList()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                sm?.storageVolumes.orEmpty()
            } else {
                emptyList()
            }
        }
        return Section(
            id = "volumes",
            title = "Storage volumes",
            subtitle = "StorageManager.getStorageVolumes()",
            facts = listOf(
                probe.value(
                    "Volume count",
                    "StorageManager.getStorageVolumes()",
                    minApi = 24,
                ) { volumes.size.takeIf { it > 0 }?.toString() },
                probe.flag(
                    "Removable volume mounted",
                    "StorageVolume.isRemovable()",
                    minApi = 24,
                    searchTerms = listOf("sd card", "microsd", "removable"),
                    supportedText = "Yes",
                    unsupportedText = "No removable volume is mounted",
                ) { volumes.any { it.isRemovable } },
            ),
            children = volumes.mapIndexed { index, volume ->
                Section(
                    id = "volume-$index",
                    title = probe.attempt("Volume ${index + 1}") {
                        volume.getDescription(context) ?: "Volume ${index + 1}"
                    },
                    facts = buildList {
                        add(
                            probe.value("State", "StorageVolume.getState()", minApi = 24) {
                                Format.titleCaseEnum(volume.state.uppercase())
                            },
                        )
                        add(
                            probe.flag("Primary", "StorageVolume.isPrimary()", minApi = 24) {
                                volume.isPrimary
                            },
                        )
                        add(
                            probe.flag("Removable", "StorageVolume.isRemovable()", minApi = 24) {
                                volume.isRemovable
                            },
                        )
                        add(
                            probe.flag("Emulated", "StorageVolume.isEmulated()", minApi = 24) {
                                volume.isEmulated
                            },
                        )
                        add(
                            probe.value("UUID", "StorageVolume.getUuid()", minApi = 24) {
                                volume.uuid
                            },
                        )
                        add(
                            probe.value(
                                "Media store volume",
                                "StorageVolume.getMediaStoreVolumeName()",
                                minApi = 30,
                            ) { volume.mediaStoreVolumeName },
                        )
                        if (Build.VERSION.SDK_INT >= 30) {
                            val dir = probe.attempt<File?>(null) { volume.directory }
                            add(
                                probe.value(
                                    "Capacity",
                                    "StatFs on StorageVolume.getDirectory()",
                                    minApi = 30,
                                ) {
                                    dir?.let { d ->
                                        val s = StatFs(d.absolutePath)
                                        Format.bytes(s.blockCountLong * s.blockSizeLong)
                                    }
                                },
                            )
                            add(
                                probe.value(
                                    "Available",
                                    "StatFs on StorageVolume.getDirectory()",
                                    minApi = 30,
                                ) {
                                    dir?.let { d ->
                                        val s = StatFs(d.absolutePath)
                                        Format.bytes(s.availableBlocksLong * s.blockSizeLong)
                                    }
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    /**
     * Filesystem identification.
     *
     * Android has no API that names a volume's filesystem, and `/proc/mounts` is the
     * only source. It is readable, but on many builds an app's mount namespace hides
     * the real data mount, so a failure to identify is reported as such.
     */
    private fun filesystem(): Section {
        val mounts = probe.attempt<String?>(null) {
            val f = File("/proc/mounts")
            if (f.canRead()) f.readText() else null
        }
        val dataFs = mounts?.let { findFilesystem(it, "/data") }
        val userdataFs = mounts?.let { findFilesystem(it, "/storage/emulated") }

        return Section(
            id = "filesystem",
            title = "Filesystem",
            subtitle = "/proc/mounts — Android exposes no filesystem-type API",
            facts = listOf(
                probe.value(
                    "Data partition filesystem",
                    "/proc/mounts",
                    absentText = Absent.NOT_EXPOSED,
                    searchTerms = listOf("f2fs", "ext4", "filesystem"),
                    detail = "Read from the process's own mount table. An app's mount " +
                        "namespace does not always include the real data mount, in " +
                        "which case this cannot be determined.",
                ) { dataFs },
                probe.value(
                    "Shared storage filesystem",
                    "/proc/mounts",
                    absentText = Absent.NOT_EXPOSED,
                    searchTerms = listOf("fuse", "sdcardfs"),
                ) { userdataFs },
                probe.flag(
                    "Encrypted storage",
                    "ro.crypto.state",
                    searchTerms = listOf("encryption", "fbe", "fde", "encrypted"),
                    supportedText = "Yes",
                    unsupportedText = "No",
                    detail = "Read from the ro.crypto.state property; Android has no API " +
                        "that reports storage-encryption state to apps.",
                ) {
                    when (SystemProperties.get("ro.crypto.state")) {
                        "encrypted" -> true
                        "unencrypted" -> false
                        else -> null
                    }
                },
                probe.value(
                    "Encryption type",
                    "ro.crypto.type",
                    searchTerms = listOf("file based encryption", "fbe", "metadata encryption"),
                ) {
                    when (val type = SystemProperties.get("ro.crypto.type")) {
                        "file" -> "File-based encryption (FBE)"
                        "block" -> "Full-disk encryption (FDE)"
                        null -> null
                        else -> type
                    }
                },
            ),
        )
    }

    internal companion object {

        /**
         * The filesystem type backing [path], from a `/proc/mounts` table.
         *
         * Three ways a line can match, in order of preference:
         *
         *  1. the mount point *is* the path — an exact answer;
         *  2. the mount point is an ancestor of the path, so it is what backs it;
         *  3. the mount point sits *under* the path, which is how a query for the
         *     prefix `/storage/emulated` finds the FUSE mount at
         *     `/storage/emulated`. This one is deliberately narrow: it requires the
         *     next character to be a separator, so a query for `/` does not "match"
         *     every mount in the table and come back with whichever happens to be
         *     deepest.
         *
         * Among genuine matches the longest mount point wins, because that is the one
         * actually backing the path. Reporting `/`'s ext4 for a query about `/data`
         * would be wrong in the worst way -- `/data` is f2fs on most modern devices,
         * so the wrong answer is also the plausible one.
         */
        internal fun findFilesystem(mounts: String, path: String): String? {
            var best: Pair<String, String>? = null
            mounts.lineSequence().forEach { line ->
                val parts = line.split(' ')
                if (parts.size < 3) return@forEach
                val mountPoint = parts[1]
                val type = parts[2]
                val covers = path == mountPoint ||
                    path.startsWith("$mountPoint/") ||
                    mountPoint.startsWith("$path/")
                if (covers) {
                    val current = best
                    if (current == null || mountPoint.length > current.first.length) {
                        best = mountPoint to type
                    }
                }
            }
            return best?.second
        }
    }
}
