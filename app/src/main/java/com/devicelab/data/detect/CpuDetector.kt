package com.devicelab.data.detect

import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.devicelab.core.common.Format
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Provenance
import com.devicelab.core.model.Section
import com.devicelab.core.model.Support
import java.io.File
import javax.inject.Inject

/**
 * CPU topology, ABIs and instruction sets.
 *
 * Android has no CPU API beyond `Build.SUPPORTED_ABIS` and
 * `Runtime.availableProcessors()`, so anything more detailed has to come from the
 * kernel's own sysfs and procfs files. Both are world-readable on Android, need no
 * permission and no root -- but their *contents* are heavily restricted from API 26
 * onward: `/proc/cpuinfo` on arm64 deliberately omits the model name, and many OEMs
 * remove `scaling_max_freq`. This detector therefore:
 *
 *  * reads each file behind [readFile], which returns null on any failure;
 *  * reports "Not exposed by Android" with the reason when a file is unreadable,
 *    rather than substituting a plausible number;
 *  * derives clusters only from `cpufreq` maximum frequencies, which is the one
 *    grouping that is real when the files exist -- never from core counts, which
 *    would be a guess dressed as a measurement.
 *
 * The frequency figures are the kernel's *policy ceilings*, not live clocks, and the
 * rows say so: an instantaneous clock read from an app process is meaningless because
 * the governor changes it thousands of times a second.
 */
class CpuDetector @Inject constructor(
    private val probe: Probe,
    private val fs: SysFs = SysFs.Real,
) : CapabilityDetector {

    override val lab = Lab.CPU

    /** Filesystem seam so cluster derivation can be unit-tested with fixtures. */
    interface SysFs {
        fun read(path: String): String?
        fun exists(path: String): Boolean

        object Real : SysFs {
            override fun read(path: String): String? = try {
                val f = File(path)
                if (f.isFile && f.canRead()) f.readText().trim().takeIf { it.isNotEmpty() } else null
            } catch (t: Throwable) {
                null
            }

            override fun exists(path: String): Boolean = try {
                File(path).exists()
            } catch (t: Throwable) {
                false
            }
        }
    }

    override suspend fun detect(): LabReport {
        val cores = Runtime.getRuntime().availableProcessors()
        val topology = readTopology(cores)
        return LabReport(
            lab = lab,
            sections = listOf(
                architecture(),
                cores(cores, topology),
                clusters(topology),
                instructionSets(),
                cpuInfoSection(),
            ),
            notes = buildList {
                if (topology.frequenciesReadable && topology.clusters.size > 1) {
                    add(
                        "Cluster grouping below is derived from each core's cpufreq policy " +
                            "ceiling. It reflects the kernel's own grouping, but Android " +
                            "exposes no API that names a core as big or little.",
                    )
                }
                if (!topology.frequenciesReadable) {
                    add(
                        "This kernel does not expose per-core frequency policy to app " +
                            "processes, so cluster structure cannot be determined here.",
                    )
                }
            },
        )
    }

    private fun architecture() = Section(
        id = "architecture",
        title = "Architecture",
        subtitle = "Build.SUPPORTED_ABIS, Os.uname()",
        facts = listOf(
            probe.value(
                "CPU architecture",
                "Os.uname().machine",
                minApi = 21,
                searchTerms = listOf("arm64", "aarch64", "armv8", "x86_64", "architecture"),
            ) { Os.uname().machine },
            probe.value(
                "Primary ABI",
                "Build.SUPPORTED_ABIS[0]",
                searchTerms = listOf("abi", "arm64-v8a", "armeabi"),
            ) { Build.SUPPORTED_ABIS.firstOrNull() },
            probe.value("All supported ABIs", "Build.SUPPORTED_ABIS") {
                Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { null }
            },
            probe.value(
                "64-bit ABIs",
                "Build.SUPPORTED_64_BIT_ABIS",
                absentText = Absent.NONE,
            ) { Build.SUPPORTED_64_BIT_ABIS.joinToString(", ").ifBlank { null } },
            probe.value(
                "32-bit ABIs",
                "Build.SUPPORTED_32_BIT_ABIS",
                absentText = Absent.NONE,
                detail = "An empty list here means a 64-bit-only device, which can no " +
                    "longer run 32-bit native code at all.",
            ) { Build.SUPPORTED_32_BIT_ABIS.joinToString(", ").ifBlank { null } },
            probe.flag(
                "This process is 64-bit",
                "Process.is64Bit()",
                minApi = 23,
                supportedText = "Yes",
                unsupportedText = "No — running as 32-bit",
            ) { android.os.Process.is64Bit() },
            probe.flag(
                "64-bit-only device",
                "Build.SUPPORTED_32_BIT_ABIS",
                searchTerms = listOf("64-bit only", "no 32-bit"),
                supportedText = "Yes — no 32-bit ABI is supported",
                unsupportedText = "No — 32-bit code is still supported",
            ) { Build.SUPPORTED_32_BIT_ABIS.isEmpty() },
            probe.value("SoC manufacturer", "Build.SOC_MANUFACTURER", minApi = 31) {
                Build.SOC_MANUFACTURER
            },
            probe.value(
                "SoC model",
                "Build.SOC_MODEL",
                minApi = 31,
                searchTerms = listOf("soc", "chipset", "snapdragon", "exynos", "dimensity", "tensor"),
            ) { Build.SOC_MODEL },
        ),
    )

    private fun cores(cores: Int, topology: Topology) = Section(
        id = "cores",
        title = "Cores",
        subtitle = "Runtime.availableProcessors(), /sys/devices/system/cpu",
        facts = listOf(
            probe.value(
                "Cores available to this process",
                "Runtime.availableProcessors()",
                searchTerms = listOf("cores", "cpu count", "octa", "hexa", "quad"),
                detail = "What the JVM may schedule on. Under a cgroup limit or with " +
                    "cores hot-unplugged this can be lower than the physical count.",
            ) { cores.toString() },
            probe.value(
                "Cores present (kernel)",
                "/sys/devices/system/cpu/present",
                detail = "The kernel's list of CPUs that physically exist, whether or " +
                    "not they are online right now.",
            ) {
                topology.presentCount?.let { count ->
                    "$count" + topology.presentRange?.let { " (range $it)" }.orEmpty()
                }
            },
            probe.value("Cores online now", "/sys/devices/system/cpu/online") {
                topology.onlineCount?.let { count ->
                    "$count" + topology.onlineRange?.let { " (range $it)" }.orEmpty()
                }
            },
            probe.value(
                "Configured processors",
                "Os.sysconf(_SC_NPROCESSORS_CONF)",
                minApi = 21,
            ) { Os.sysconf(OsConstants._SC_NPROCESSORS_CONF).toString() },
            probe.value(
                "Online processors",
                "Os.sysconf(_SC_NPROCESSORS_ONLN)",
                minApi = 21,
            ) { Os.sysconf(OsConstants._SC_NPROCESSORS_ONLN).toString() },
            probe.notExposedByAndroid(
                "Live core clock",
                "an instantaneous clock read by an app is meaningless — the governor " +
                    "changes it far faster than it can be sampled, and scaling_cur_freq " +
                    "is unreadable to apps on most kernels since API 26",
                searchTerms = listOf("clock speed", "ghz", "frequency", "current frequency"),
            ),
        ),
    )

    private fun clusters(topology: Topology): Section {
        if (!topology.frequenciesReadable) {
            return Section(
                id = "clusters",
                title = "Cluster structure",
                subtitle = "Derived from cpufreq policy ceilings",
                facts = listOf(
                    Fact(
                        label = "Cluster structure",
                        value = Absent.NOT_EXPOSED,
                        provenance = Provenance.Restricted(
                            "/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq",
                            "this kernel does not expose cpufreq to app processes",
                        ),
                        support = Support.NOT_EXPOSED,
                        detail = "Android has no API for CPU cluster topology. The only " +
                            "reliable source is the kernel's cpufreq policy files, and " +
                            "reading them is blocked here — so whether this device is " +
                            "big.LITTLE cannot be established, and will not be guessed.",
                        searchTerms = listOf("big.little", "cluster", "big little", "prime core"),
                    ),
                ),
            )
        }

        val clusters = topology.clusters
        return Section(
            id = "clusters",
            title = "Cluster structure",
            subtitle = "Derived from cpufreq policy ceilings",
            facts = listOf(
                probe.value(
                    "Topology",
                    "/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq",
                    searchTerms = listOf("big.little", "cluster", "heterogeneous", "prime core"),
                ) {
                    when (clusters.size) {
                        0 -> null
                        1 -> "Homogeneous — all ${clusters[0].cpus.size} cores share one " +
                            "frequency ceiling"
                        else -> "Heterogeneous (big.LITTLE) — ${clusters.size} clusters: " +
                            clusters.joinToString(" + ") { "${it.cpus.size}×${it.maxMhz} MHz" }
                    }
                },
                probe.value("Cluster count", "cpufreq policy grouping") {
                    clusters.size.takeIf { it > 0 }?.toString()
                },
                probe.value(
                    "Peak policy ceiling",
                    "cpuinfo_max_freq",
                    detail = "The highest frequency the kernel will allow any core to " +
                        "reach. It is a policy limit, not a measurement of the clock now.",
                ) { clusters.maxByOrNull { it.maxKhz }?.let { "${it.maxMhz} MHz" } },
            ),
            children = clusters.mapIndexed { index, cluster ->
                Section(
                    id = "cluster-$index",
                    title = "Cluster ${index + 1} — ${cluster.cpus.size} core" +
                        if (cluster.cpus.size == 1) "" else "s",
                    facts = listOfNotNull(
                        probe.value("CPUs", "/sys/devices/system/cpu") {
                            cluster.cpus.joinToString(", ") { "cpu$it" }
                        },
                        probe.value("Maximum policy frequency", "cpuinfo_max_freq") {
                            "${cluster.maxMhz} MHz"
                        },
                        cluster.minKhz?.let { min ->
                            probe.value("Minimum policy frequency", "cpuinfo_min_freq") {
                                "${min / 1000} MHz"
                            }
                        },
                        cluster.governor?.let { governor ->
                            probe.value("Governor", "cpufreq/scaling_governor") { governor }
                        },
                        cluster.availableFrequencies?.let { freqs ->
                            probe.value(
                                "Available frequencies",
                                "cpufreq/scaling_available_frequencies",
                            ) {
                                freqs.joinToString(", ") { "${it / 1000}" } + " MHz"
                            }
                        },
                    ),
                )
            },
        )
    }

    /**
     * Instruction-set extensions from the kernel's own `Features` line.
     *
     * On arm64 `/proc/cpuinfo` still lists hardware capabilities even where it hides
     * the model name, and those flags are the kernel's view of what the silicon
     * advertises -- so a positive here is real. When the line is missing, each row
     * says the flags are unreadable instead of reporting "unsupported", because on
     * an ARMv8 device most of these are in fact mandatory.
     */
    private fun instructionSets(): Section {
        val features = readCpuFeatures()
        fun featureFact(label: String, flag: String, terms: List<String> = emptyList()): Fact {
            if (features == null) {
                return Fact(
                    label = label,
                    value = Absent.NOT_EXPOSED,
                    provenance = Provenance.Restricted(
                        "/proc/cpuinfo Features",
                        "the flags line is not readable by app processes on this kernel",
                    ),
                    support = Support.NOT_EXPOSED,
                    searchTerms = terms + flag,
                )
            }
            return probe.flag(label, "/proc/cpuinfo Features", searchTerms = terms + flag) {
                features.contains(flag)
            }
        }

        return Section(
            id = "instruction-sets",
            title = "Instruction-set extensions",
            subtitle = "/proc/cpuinfo Features",
            facts = listOf(
                featureFact("NEON / ASIMD", "asimd", listOf("neon", "simd")),
                featureFact("AES", "aes", listOf("crypto", "aes-ni")),
                featureFact("SHA-1", "sha1", listOf("crypto", "hash")),
                featureFact("SHA-2", "sha2", listOf("crypto", "hash")),
                featureFact("CRC32", "crc32"),
                featureFact("Atomics (LSE)", "atomics", listOf("lse", "large system extensions")),
                featureFact("Half-precision FP", "fphp", listOf("fp16")),
                featureFact("Dot product", "asimddp", listOf("dotprod", "int8")),
                featureFact("FP16 arithmetic", "asimdhp", listOf("fp16", "half precision")),
                featureFact("BF16", "bf16", listOf("bfloat16", "ml")),
                featureFact("I8MM", "i8mm", listOf("int8 matrix", "ml")),
                featureFact("SVE", "sve", listOf("scalable vector")),
                featureFact("Pointer authentication", "paca", listOf("pac", "pauth", "security")),
                featureFact("Branch target identification", "bti", listOf("security")),
                featureFact("Memory tagging (MTE)", "mte", listOf("mte", "security", "tagging")),
            ),
        )
    }

    private fun cpuInfoSection(): Section {
        val raw = fs.read("/proc/cpuinfo")
        if (raw == null) {
            return Section(
                id = "cpuinfo",
                title = "Kernel CPU report",
                facts = listOf(
                    Fact(
                        label = "/proc/cpuinfo",
                        value = Absent.UNAVAILABLE,
                        provenance = Provenance.Restricted(
                            "/proc/cpuinfo",
                            "not readable from this app process",
                        ),
                        support = Support.NOT_EXPOSED,
                    ),
                ),
            )
        }
        val fields = parseCpuInfoFields(raw)
        return Section(
            id = "cpuinfo",
            title = "Kernel CPU report",
            subtitle = "/proc/cpuinfo",
            facts = buildList {
                add(
                    probe.value(
                        "Model name",
                        "/proc/cpuinfo model name",
                        detail = "arm64 kernels deliberately omit a model-name line; a " +
                            "blank here is Android's design, not a read failure.",
                        searchTerms = listOf("model name", "cpu model"),
                    ) { fields["model name"] ?: fields["Processor"] },
                )
                add(
                    probe.value("CPU implementer", "/proc/cpuinfo CPU implementer") {
                        fields["CPU implementer"]?.let { "$it${implementerName(it)}" }
                    },
                )
                add(probe.value("CPU architecture", "/proc/cpuinfo CPU architecture") {
                    fields["CPU architecture"]
                })
                add(probe.value("CPU variant", "/proc/cpuinfo CPU variant") { fields["CPU variant"] })
                add(probe.value("CPU part", "/proc/cpuinfo CPU part") {
                    fields["CPU part"]?.let { part -> "$part${partName(part)}" }
                })
                add(probe.value("CPU revision", "/proc/cpuinfo CPU revision") {
                    fields["CPU revision"]
                })
                add(probe.value("Hardware", "/proc/cpuinfo Hardware") { fields["Hardware"] })
                add(probe.value("Features", "/proc/cpuinfo Features") { fields["Features"] })
                add(probe.value("BogoMIPS", "/proc/cpuinfo BogoMIPS") {
                    fields["BogoMIPS"]?.let {
                        "$it — a kernel calibration loop, not a performance figure"
                    }
                })
            },
        )
    }

    // ---- topology reading -------------------------------------------------

    internal data class Cluster(
        val cpus: List<Int>,
        val maxKhz: Long,
        val minKhz: Long?,
        val governor: String?,
        val availableFrequencies: List<Long>?,
    ) {
        val maxMhz: Long get() = maxKhz / 1000
    }

    internal data class Topology(
        val clusters: List<Cluster>,
        val frequenciesReadable: Boolean,
        val presentCount: Int?,
        val presentRange: String?,
        val onlineCount: Int?,
        val onlineRange: String?,
    )

    private fun readTopology(fallbackCores: Int): Topology {
        val present = fs.read("/sys/devices/system/cpu/present")
        val online = fs.read("/sys/devices/system/cpu/online")
        val count = parseCpuRange(present)?.size ?: fallbackCores

        val perCpu = (0 until count).mapNotNull { cpu ->
            val base = "/sys/devices/system/cpu/cpu$cpu/cpufreq"
            val max = fs.read("$base/cpuinfo_max_freq")?.toLongOrNull() ?: return@mapNotNull null
            CpuFreq(
                cpu = cpu,
                maxKhz = max,
                minKhz = fs.read("$base/cpuinfo_min_freq")?.toLongOrNull(),
                governor = fs.read("$base/scaling_governor"),
                available = fs.read("$base/scaling_available_frequencies")
                    ?.split(Regex("\\s+"))
                    ?.mapNotNull { it.toLongOrNull() }
                    ?.sorted()
                    ?.takeIf { it.isNotEmpty() },
            )
        }

        return Topology(
            clusters = groupClusters(perCpu),
            frequenciesReadable = perCpu.isNotEmpty(),
            presentCount = parseCpuRange(present)?.size,
            presentRange = present,
            onlineCount = parseCpuRange(online)?.size,
            onlineRange = online,
        )
    }

    internal data class CpuFreq(
        val cpu: Int,
        val maxKhz: Long,
        val minKhz: Long?,
        val governor: String?,
        val available: List<Long>?,
    )

    /** Groups cores by identical frequency ceiling, ordered slowest cluster first. */
    internal fun groupClusters(freqs: List<CpuFreq>): List<Cluster> =
        freqs.groupBy { it.maxKhz }
            .toSortedMap()
            .map { (maxKhz, group) ->
                Cluster(
                    cpus = group.map { it.cpu }.sorted(),
                    maxKhz = maxKhz,
                    minKhz = group.firstNotNullOfOrNull { it.minKhz },
                    governor = group.firstNotNullOfOrNull { it.governor },
                    availableFrequencies = group.firstNotNullOfOrNull { it.available },
                )
            }

    /** "0-7" or "0-3,4-7" or "0" → the set of CPU indices. */
    internal fun parseCpuRange(spec: String?): List<Int>? {
        if (spec.isNullOrBlank()) return null
        val out = LinkedHashSet<Int>()
        spec.trim().split(',').forEach { part ->
            val piece = part.trim()
            if (piece.isEmpty()) return@forEach
            if (piece.contains('-')) {
                val bounds = piece.split('-')
                val from = bounds.getOrNull(0)?.trim()?.toIntOrNull()
                val to = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                if (from != null && to != null && to >= from) out.addAll(from..to)
            } else {
                piece.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out.toList().takeIf { it.isNotEmpty() }
    }

    internal fun parseCpuInfoFields(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty() && !out.containsKey(key)) out[key] = value
        }
        return out
    }

    private fun readCpuFeatures(): Set<String>? {
        val raw = fs.read("/proc/cpuinfo") ?: return null
        val line = parseCpuInfoFields(raw)["Features"] ?: return null
        return line.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
    }

    /** ARM's registered implementer codes, from the ARM ARM. */
    private fun implementerName(code: String): String = when (code.lowercase()) {
        "0x41" -> " (ARM)"
        "0x51" -> " (Qualcomm)"
        "0x53" -> " (Samsung)"
        "0x4e" -> " (NVIDIA)"
        "0x56" -> " (Marvell)"
        "0x61" -> " (Apple)"
        "0x69" -> " (Intel)"
        "0xc0" -> " (Ampere)"
        else -> ""
    }

    /**
     * ARM Cortex part numbers. This is a lookup of ARM's own published MIDR part
     * codes -- the number is read from the kernel, and only its name is resolved
     * here, so nothing is inferred about a device that does not report a part.
     */
    private fun partName(part: String): String = when (part.lowercase()) {
        "0xd03" -> " (Cortex-A53)"
        "0xd04" -> " (Cortex-A35)"
        "0xd05" -> " (Cortex-A55)"
        "0xd07" -> " (Cortex-A57)"
        "0xd08" -> " (Cortex-A72)"
        "0xd09" -> " (Cortex-A73)"
        "0xd0a" -> " (Cortex-A75)"
        "0xd0b" -> " (Cortex-A76)"
        "0xd0d" -> " (Cortex-A77)"
        "0xd41" -> " (Cortex-A78)"
        "0xd44" -> " (Cortex-X1)"
        "0xd46" -> " (Cortex-A510)"
        "0xd47" -> " (Cortex-A710)"
        "0xd48" -> " (Cortex-X2)"
        "0xd4d" -> " (Cortex-A715)"
        "0xd4e" -> " (Cortex-X3)"
        "0xd80" -> " (Cortex-A520)"
        "0xd81" -> " (Cortex-A720)"
        "0xd82" -> " (Cortex-X4)"
        else -> ""
    }
}
