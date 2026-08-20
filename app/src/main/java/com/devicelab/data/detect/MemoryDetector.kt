package com.devicelab.data.detect

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import com.devicelab.core.common.Format
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Provenance
import com.devicelab.core.model.Section
import com.devicelab.core.model.Support
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * RAM totals, the platform's memory classes, and this process's heap.
 *
 * `ActivityManager.MemoryInfo.totalMem` is the authoritative total, and it is
 * deliberately *not* the number printed on the box: the kernel reserves memory for
 * itself and for the GPU before Android ever sees it, so a "12 GB" phone reports
 * around 11.1 GiB here. The row says so, because the alternative -- rounding up to
 * the marketing figure -- would be fabricating a number the device never reported.
 */
class MemoryDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.MEMORY

    /** The live figures, re-read on every manual refresh. */
    data class Snapshot(
        val totalBytes: Long,
        val availableBytes: Long,
        val usedBytes: Long,
        val thresholdBytes: Long,
        val lowMemory: Boolean,
        val heapMaxBytes: Long,
        val heapUsedBytes: Long,
        val nativeHeapBytes: Long,
    ) {
        val usedFraction: Double
            get() = if (totalBytes <= 0) 0.0 else usedBytes.toDouble() / totalBytes
    }

    fun snapshot(): Snapshot? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return try {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            Snapshot(
                totalBytes = info.totalMem,
                availableBytes = info.availMem,
                usedBytes = (info.totalMem - info.availMem).coerceAtLeast(0),
                thresholdBytes = info.threshold,
                lowMemory = info.lowMemory,
                heapMaxBytes = runtime.maxMemory(),
                heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            )
        } catch (t: Throwable) {
            null
        }
    }

    override suspend fun detect(): LabReport {
        val am = context.getSystemService(ActivityManager::class.java)
        val snap = snapshot()
        return LabReport(
            lab = lab,
            sections = listOf(
                systemMemory(snap),
                memoryClasses(am),
                runtimeHeap(snap),
            ),
        )
    }

    private fun systemMemory(snap: Snapshot?) = Section(
        id = "system-memory",
        title = "System RAM",
        subtitle = "ActivityManager.getMemoryInfo()",
        facts = listOf(
            probe.value(
                "Total RAM",
                "MemoryInfo.totalMem",
                minApi = 16,
                searchTerms = listOf("ram", "memory", "total memory"),
                detail = "Total memory the kernel makes available to Android. Lower than " +
                    "the advertised capacity because firmware, the GPU and the kernel " +
                    "reserve their share before this is counted.",
            ) { snap?.let { Format.bytes(it.totalBytes) } },
            probe.value(
                "Available RAM",
                "MemoryInfo.availMem",
                searchTerms = listOf("free ram", "available memory"),
            ) { snap?.let { Format.bytes(it.availableBytes) } },
            probe.value("Used RAM", "totalMem − availMem") {
                snap?.let { "${Format.bytes(it.usedBytes)} (${Format.percent(it.usedFraction)})" }
            },
            probe.value(
                "Low-memory threshold",
                "MemoryInfo.threshold",
                detail = "Below this much free memory the platform starts killing " +
                    "background processes.",
            ) { snap?.let { Format.bytes(it.thresholdBytes) } },
            probe.flag(
                "Currently low on memory",
                "MemoryInfo.lowMemory",
                supportedText = "Yes — the platform is reclaiming memory",
                unsupportedText = "No",
            ) { snap?.lowMemory },
        ),
    )

    private fun memoryClasses(am: ActivityManager?) = Section(
        id = "memory-classes",
        title = "Platform memory classes",
        subtitle = "ActivityManager",
        facts = listOf(
            probe.value(
                "Standard heap limit",
                "ActivityManager.getMemoryClass()",
                detail = "The per-app Java heap ceiling an ordinary app gets.",
            ) { am?.memoryClass?.takeIf { it > 0 }?.let { "$it MB" } },
            probe.value(
                "Large heap limit",
                "ActivityManager.getLargeMemoryClass()",
                detail = "The ceiling for an app that sets android:largeHeap=\"true\".",
            ) { am?.largeMemoryClass?.takeIf { it > 0 }?.let { "$it MB" } },
            probe.flag(
                "Large-heap headroom",
                "getLargeMemoryClass() > getMemoryClass()",
                supportedText = "Available",
                unsupportedText = "None — largeHeap grants no extra memory here",
            ) {
                val standard = am?.memoryClass ?: return@flag null
                val large = am.largeMemoryClass
                large > standard
            },
            probe.flag(
                "Low-RAM device",
                "ActivityManager.isLowRamDevice()",
                minApi = 19,
                searchTerms = listOf("low ram", "go edition", "android go"),
                supportedText = "Yes — the platform requests reduced memory use",
                unsupportedText = "No",
            ) { am?.isLowRamDevice },
        ),
    )

    private fun runtimeHeap(snap: Snapshot?) = Section(
        id = "runtime-heap",
        title = "This process",
        subtitle = "Runtime, Debug — the inspector's own footprint",
        facts = listOf(
            probe.value("Heap maximum", "Runtime.maxMemory()") {
                snap?.let { Format.bytes(it.heapMaxBytes) }
            },
            probe.value("Heap in use", "Runtime.totalMemory() − freeMemory()") {
                snap?.let { Format.bytes(it.heapUsedBytes) }
            },
            probe.value("Native heap", "Debug.getNativeHeapAllocatedSize()") {
                snap?.let { Format.bytes(it.nativeHeapBytes) }
            },
            probe.value("PSS of this process", "Debug.MemoryInfo.getTotalPss()") {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                Format.bytes(info.totalPss * 1024L)
            },
            probe.value(
                "Private dirty",
                "Debug.MemoryInfo.getTotalPrivateDirty()",
            ) {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                Format.bytes(info.totalPrivateDirty * 1024L)
            },
        ),
    )
}
