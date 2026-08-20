package com.devicelab.data.detect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import com.devicelab.core.common.AndroidVersions
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Provenance
import com.devicelab.core.model.Section
import com.devicelab.core.model.Support
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Android platform, build and OS-image facts.
 *
 * Treble / A/B / dynamic-partition detection deserves a note: Android exposes no
 * public API for any of the three. The values used here are the same read-only
 * system properties that the platform's own build system sets and that
 * `PackageManager` cannot answer -- read through [SystemProperties], which uses the
 * public `Os.getenv`-adjacent path where possible and reflection on
 * `android.os.SystemProperties` otherwise. Where the property is missing the answer
 * is reported as unknown, never inferred from the device's age.
 */
class PlatformDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.PLATFORM

    override suspend fun detect(): LabReport {
        val pm = context.packageManager
        return LabReport(
            lab = lab,
            sections = listOf(
                identity(),
                version(),
                abis(),
                osImage(),
                kernel(pm),
            ),
        )
    }

    private fun identity() = Section(
        id = "identity",
        title = "Device identity",
        subtitle = "android.os.Build",
        facts = listOf(
            probe.value("Marketing name", "Build.MODEL") { Build.MODEL },
            probe.value("Manufacturer", "Build.MANUFACTURER") { Build.MANUFACTURER },
            probe.value("Brand", "Build.BRAND") { Build.BRAND },
            probe.value("Model", "Build.MODEL") { Build.MODEL },
            probe.value("Device", "Build.DEVICE") { Build.DEVICE },
            probe.value("Product", "Build.PRODUCT") { Build.PRODUCT },
            probe.value("Board", "Build.BOARD") { Build.BOARD },
            probe.value("Hardware", "Build.HARDWARE") { Build.HARDWARE },
            probe.value("SoC manufacturer", "Build.SOC_MANUFACTURER", minApi = 31) {
                Build.SOC_MANUFACTURER
            },
            probe.value("SoC model", "Build.SOC_MODEL", minApi = 31) { Build.SOC_MODEL },
            probe.value("SKU", "Build.SKU", minApi = 31) { Build.SKU },
            probe.value("ODM SKU", "Build.ODM_SKU", minApi = 31) { Build.ODM_SKU },
        ),
    )

    private fun version() = Section(
        id = "version",
        title = "Android version",
        subtitle = "android.os.Build.VERSION",
        facts = listOf(
            probe.value("Android version", "Build.VERSION.RELEASE") {
                AndroidVersions.describe(Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
            },
            probe.value("API level", "Build.VERSION.SDK_INT") { Build.VERSION.SDK_INT.toString() },
            probe.value("Release", "Build.VERSION.RELEASE") { Build.VERSION.RELEASE },
            probe.value(
                "Release or codename",
                "Build.VERSION.RELEASE_OR_CODENAME",
                minApi = 30,
            ) { Build.VERSION.RELEASE_OR_CODENAME },
            probe.value("Codename", "Build.VERSION.CODENAME") { Build.VERSION.CODENAME },
            probe.value("Incremental", "Build.VERSION.INCREMENTAL") { Build.VERSION.INCREMENTAL },
            probe.value("Security patch", "Build.VERSION.SECURITY_PATCH") {
                Build.VERSION.SECURITY_PATCH
            },
            probe.value(
                "Vendor security patch",
                "ro.vendor.build.security_patch",
                minApi = 30,
            ) { SystemProperties.get("ro.vendor.build.security_patch") },
            probe.value("Base OS", "Build.VERSION.BASE_OS") { Build.VERSION.BASE_OS },
            probe.value("Media performance class", "Build.VERSION.MEDIA_PERFORMANCE_CLASS", minApi = 31) {
                val cls = Build.VERSION.MEDIA_PERFORMANCE_CLASS
                if (cls == 0) null else "Class $cls (API $cls)"
            },
            probe.value("Build fingerprint", "Build.FINGERPRINT") { Build.FINGERPRINT },
            probe.value("Build ID", "Build.ID") { Build.ID },
            probe.value("Build tags", "Build.TAGS") { Build.TAGS },
            probe.value("Build type", "Build.TYPE") { Build.TYPE },
            probe.value("Bootloader", "Build.BOOTLOADER") { Build.BOOTLOADER },
            probe.value("Radio/baseband", "Build.getRadioVersion()") { Build.getRadioVersion() },
        ),
    )

    private fun abis() = Section(
        id = "abis",
        title = "Supported ABIs",
        subtitle = "Build.SUPPORTED_*_ABIS",
        facts = listOf(
            probe.value("Primary ABI", "Build.SUPPORTED_ABIS[0]") {
                Build.SUPPORTED_ABIS.firstOrNull()
            },
            probe.value("All ABIs", "Build.SUPPORTED_ABIS") {
                Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { null }
            },
            probe.value("64-bit ABIs", "Build.SUPPORTED_64_BIT_ABIS", absentText = Absent.NONE) {
                Build.SUPPORTED_64_BIT_ABIS.joinToString(", ").ifBlank { null }
            },
            probe.value("32-bit ABIs", "Build.SUPPORTED_32_BIT_ABIS", absentText = Absent.NONE) {
                Build.SUPPORTED_32_BIT_ABIS.joinToString(", ").ifBlank { null }
            },
            probe.flag(
                "64-bit environment",
                "Process.is64Bit()",
                minApi = 23,
                supportedText = "This process is 64-bit",
                unsupportedText = "This process is 32-bit",
            ) { android.os.Process.is64Bit() },
        ),
    )

    /**
     * OS-image structure. Every fact here is a system property because Android has
     * no API for any of it; each row names the property it read so the claim is
     * checkable, and a missing property reads as unknown rather than as "no".
     */
    private fun osImage(): Section {
        val facts = mutableListOf<Fact>()

        facts += SystemProperties.verdict(
            probe = probe,
            label = "Treble (vendor interface)",
            property = "ro.treble.enabled",
            searchTerms = listOf("treble", "vndk", "vendor interface"),
        )
        facts += probe.value("VNDK version", "ro.vndk.version") {
            SystemProperties.get("ro.vndk.version")
        }
        facts += probe.verdict(
            "A/B seamless updates",
            "ro.build.ab_update",
            searchTerms = listOf("a/b", "seamless", "ota", "slot"),
        ) {
            val ab = SystemProperties.get("ro.build.ab_update")
            val slotSuffix = SystemProperties.get("ro.boot.slot_suffix")
            when {
                ab == "true" -> Probe.Verdict.yes(
                    "Supported",
                    "ro.build.ab_update=true" +
                        (slotSuffix?.let { "; current slot suffix \"$it\"" } ?: ""),
                )
                ab == "false" -> Probe.Verdict.no("Not supported", "ro.build.ab_update=false")
                !slotSuffix.isNullOrBlank() -> Probe.Verdict.yes(
                    "Supported",
                    "Inferred from a non-empty ro.boot.slot_suffix (\"$slotSuffix\"); " +
                        "ro.build.ab_update is unset",
                )
                else -> null
            }
        }
        facts += SystemProperties.verdict(
            probe = probe,
            label = "Dynamic partitions",
            property = "ro.boot.dynamic_partitions",
            searchTerms = listOf("dynamic partitions", "super partition", "logical"),
        )
        facts += SystemProperties.verdict(
            probe = probe,
            label = "Virtual A/B",
            property = "ro.virtual_ab.enabled",
            searchTerms = listOf("virtual a/b", "snapuserd"),
        )
        facts += probe.value("Verified boot state", "ro.boot.verifiedbootstate") {
            SystemProperties.get("ro.boot.verifiedbootstate")
        }
        facts += probe.value("Bootloader lock state", "ro.boot.flash.locked") {
            when (SystemProperties.get("ro.boot.flash.locked")) {
                "1" -> "Locked"
                "0" -> "Unlocked"
                else -> null
            }
        }
        facts += probe.value("Zygote", "ro.zygote") { SystemProperties.get("ro.zygote") }
        facts += probe.value("Build date", "ro.build.date") {
            SystemProperties.get("ro.build.date")
        }
        facts += probe.value("First API level", "ro.product.first_api_level") {
            SystemProperties.get("ro.product.first_api_level")?.let {
                "$it (${AndroidVersions.name(it.toIntOrNull() ?: 0)})"
            }
        }

        return Section(
            id = "os-image",
            title = "OS image",
            subtitle = "Read-only system properties — Android exposes no API for these",
            facts = facts,
        )
    }

    private fun kernel(pm: PackageManager) = Section(
        id = "kernel",
        title = "Kernel & runtime",
        facts = listOf(
            probe.value("Kernel", "os.version (System.getProperty)") {
                System.getProperty("os.version")
            },
            probe.value("Kernel release", "Os.uname().release", minApi = 21) {
                Os.uname().release
            },
            probe.value("Kernel version", "Os.uname().version", minApi = 21) {
                Os.uname().version
            },
            probe.value("Machine", "Os.uname().machine", minApi = 21) { Os.uname().machine },
            probe.value("Kernel banner", "/proc/version") {
                readProc("/proc/version")?.trim()
            },
            probe.value("ART/Dalvik VM", "java.vm.version") {
                val name = System.getProperty("java.vm.name")
                val version = System.getProperty("java.vm.version")
                listOfNotNull(name, version).joinToString(" ").ifBlank { null }
            },
            probe.value("Bionic/libc", "Os.uname().sysname", minApi = 21) { Os.uname().sysname },
            probe.value("Page size", "Os.sysconf(_SC_PAGESIZE)", minApi = 21) {
                "${Os.sysconf(android.system.OsConstants._SC_PAGESIZE)} bytes"
            },
            probe.value("Uptime", "SystemClock.elapsedRealtime()") {
                val ms = android.os.SystemClock.elapsedRealtime()
                val h = ms / 3_600_000
                val m = (ms % 3_600_000) / 60_000
                "${h}h ${m}m"
            },
            probe.value("Boot mode", "ro.boot.mode") { SystemProperties.get("ro.boot.mode") },
            Fact(
                label = "Root access",
                value = "Not required",
                provenance = Provenance.Queried("—"),
                support = Support.INFORMATIONAL,
                detail = "Every value in this report comes from a public API or a " +
                    "world-readable system property. The app does not test for, " +
                    "request or use root.",
            ),
        ),
    )

    private fun readProc(path: String): String? = try {
        val f = File(path)
        if (f.canRead()) f.readText().take(400) else null
    } catch (t: Throwable) {
        null
    }
}
