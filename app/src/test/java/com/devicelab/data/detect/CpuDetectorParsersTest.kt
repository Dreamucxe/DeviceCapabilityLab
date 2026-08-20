package com.devicelab.data.detect

import com.devicelab.core.detect.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kernel-file parsers behind the CPU lab.
 *
 * Android exposes almost nothing about the CPU, so everything beyond the ABI list and
 * the core count comes from parsing sysfs and procfs. Those files are world-readable but
 * their *contents* vary enormously: arm64 `/proc/cpuinfo` omits the model name entirely,
 * many OEMs remove `scaling_max_freq`, and a range spec can be "0-7", "0-3,4-7" or "0".
 *
 * Every test here feeds a fixture through the [CpuDetector.SysFs] seam or straight into a
 * parser, because the alternative -- reading this host's own `/proc` -- would assert
 * whatever this particular machine happens to have.
 */
class CpuDetectorParsersTest {

    private val detector = CpuDetector(Probe(deviceApi = 34), FakeSysFs(emptyMap()))

    // ------------------------------------------------------------- CPU ranges

    @Test
    fun `a simple range expands to every index in it`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), detector.parseCpuRange("0-7"))
    }

    @Test
    fun `a single index parses to a list of one`() {
        assertEquals(listOf(0), detector.parseCpuRange("0"))
        assertEquals(listOf(3), detector.parseCpuRange("3"))
    }

    @Test
    fun `a comma separated spec expands every part`() {
        assertEquals(listOf(0, 1, 2, 3, 6, 7), detector.parseCpuRange("0-3,6-7"))
        assertEquals(listOf(0, 4, 5), detector.parseCpuRange("0,4-5"))
        assertEquals(listOf(0, 2, 4), detector.parseCpuRange("0,2,4"))
    }

    /** Offlining cores leaves gaps, so a non-contiguous spec is normal, not malformed. */
    @Test
    fun `a spec with gaps keeps the gaps`() {
        assertEquals(listOf(0, 1, 6, 7), detector.parseCpuRange("0-1,6-7"))
    }

    @Test
    fun `surrounding and internal whitespace is tolerated`() {
        assertEquals(listOf(0, 1, 2, 3), detector.parseCpuRange(" 0-3 \n"))
        assertEquals(listOf(0, 1, 4), detector.parseCpuRange("0-1, 4"))
    }

    @Test
    fun `an index appearing twice is counted once`() {
        assertEquals(listOf(0, 1, 2), detector.parseCpuRange("0-2,1"))
    }

    /** Null rather than an empty list or a zero: the file was not readable. */
    @Test
    fun `an absent or unusable spec is null`() {
        assertNull(detector.parseCpuRange(null))
        assertNull(detector.parseCpuRange(""))
        assertNull(detector.parseCpuRange("   "))
        assertNull(detector.parseCpuRange("not a range"))
        assertNull(detector.parseCpuRange(","))
    }

    @Test
    fun `a backwards range is discarded rather than expanded`() {
        assertNull(detector.parseCpuRange("7-0"))
        // A good part alongside a bad one keeps the good part.
        assertEquals(listOf(0, 1), detector.parseCpuRange("7-0,0-1"))
    }

    // --------------------------------------------------------------- cpuinfo

    @Test
    fun `cpuinfo fields are keyed by their label`() {
        val fields = detector.parseCpuInfoFields(ARM64_CPUINFO)
        assertEquals("AArch64 Processor rev 1 (aarch64)", fields["Processor"])
        assertEquals("0xd0b", fields["CPU part"])
        assertEquals("0x41", fields["CPU implementer"])
    }

    /** A value containing a colon -- a timestamp, a path -- must survive intact. */
    @Test
    fun `only the first colon splits a line`() {
        val fields = detector.parseCpuInfoFields("Model name: Cortex-A78: rev 1\n")
        assertEquals("Cortex-A78: rev 1", fields["Model name"])
    }

    /**
     * `/proc/cpuinfo` repeats its block once per core. The first occurrence wins, so a
     * per-core value cannot be silently attributed to the whole chip by the last core
     * that happened to be listed.
     */
    @Test
    fun `the first occurrence of a repeated key wins`() {
        val fields = detector.parseCpuInfoFields(
            """
            processor	: 0
            CPU part	: 0xd03
            processor	: 4
            CPU part	: 0xd0b
            """.trimIndent()
        )
        assertEquals("0", fields["processor"])
        assertEquals("0xd03", fields["CPU part"])
    }

    @Test
    fun `lines without a colon and empty values are skipped`() {
        val fields = detector.parseCpuInfoFields(
            """
            Hardware	: Qualcomm Technologies, Inc SM8550

            a line with no colon
            Revision	:
            : value with no key
            Serial		: 0000000000000000
            """.trimIndent()
        )
        assertEquals(2, fields.size)
        assertEquals("Qualcomm Technologies, Inc SM8550", fields["Hardware"])
        assertTrue(fields.containsKey("Serial"))
        assertTrue("an empty value must not become a key", !fields.containsKey("Revision"))
    }

    @Test
    fun `an empty cpuinfo yields an empty map rather than throwing`() {
        assertEquals(emptyMap<String, String>(), detector.parseCpuInfoFields(""))
    }

    @Test
    fun `field order is file order`() {
        val keys = detector.parseCpuInfoFields(ARM64_CPUINFO).keys.toList()
        assertEquals(listOf("Processor", "BogoMIPS", "Features", "CPU implementer", "CPU part"), keys)
    }

    // ---------------------------------------------------------- cluster grouping

    /**
     * Clusters are derived from the frequency ceiling and nothing else. Deriving them
     * from core counts -- "8 cores, so 4+4" -- would be a guess presented as a
     * measurement, which is the one thing this app must not do.
     */
    @Test
    fun `cores sharing a frequency ceiling form one cluster`() {
        val clusters = detector.groupClusters(
            listOf(
                freq(0, 1_804_800), freq(1, 1_804_800), freq(2, 1_804_800), freq(3, 1_804_800),
                freq(4, 2_803_200), freq(5, 2_803_200), freq(6, 2_803_200),
                freq(7, 3_187_200),
            )
        )
        assertEquals(3, clusters.size)
        assertEquals(listOf(0, 1, 2, 3), clusters[0].cpus)
        assertEquals(listOf(4, 5, 6), clusters[1].cpus)
        assertEquals(listOf(7), clusters[2].cpus)
    }

    @Test
    fun `clusters are ordered slowest first`() {
        val clusters = detector.groupClusters(
            listOf(freq(0, 3_187_200), freq(1, 1_804_800), freq(2, 2_803_200))
        )
        assertEquals(listOf(1_804_800L, 2_803_200L, 3_187_200L), clusters.map { it.maxKhz })
    }

    @Test
    fun `cpu indices within a cluster are sorted even when the input is not`() {
        val clusters = detector.groupClusters(
            listOf(freq(7, 1_800_000), freq(2, 1_800_000), freq(5, 1_800_000))
        )
        assertEquals(listOf(2, 5, 7), clusters.single().cpus)
    }

    @Test
    fun `khz becomes mhz for display`() {
        assertEquals(3_187L, detector.groupClusters(listOf(freq(0, 3_187_200))).single().maxMhz)
        assertEquals(1_804L, detector.groupClusters(listOf(freq(0, 1_804_800))).single().maxMhz)
    }

    /**
     * OEMs strip these files unevenly: `cpuinfo_min_freq` may be present for cpu4 and
     * missing for cpu5. Taking the first non-null within the cluster reports the value
     * that exists rather than dropping the whole row.
     */
    @Test
    fun `a value missing on one core is taken from another in the same cluster`() {
        val clusters = detector.groupClusters(
            listOf(
                CpuDetector.CpuFreq(0, 1_800_000, null, null, null),
                CpuDetector.CpuFreq(1, 1_800_000, 300_000, "schedutil", listOf(300_000, 1_800_000)),
            )
        )
        val cluster = clusters.single()
        assertEquals(300_000L, cluster.minKhz)
        assertEquals("schedutil", cluster.governor)
        assertEquals(listOf(300_000L, 1_800_000L), cluster.availableFrequencies)
    }

    @Test
    fun `a value missing on every core in a cluster stays null`() {
        val cluster = detector.groupClusters(
            listOf(
                CpuDetector.CpuFreq(0, 1_800_000, null, null, null),
                CpuDetector.CpuFreq(1, 1_800_000, null, null, null),
            )
        ).single()
        assertNull(cluster.minKhz)
        assertNull(cluster.governor)
        assertNull(cluster.availableFrequencies)
    }

    @Test
    fun `no readable frequencies means no clusters rather than one invented cluster`() {
        assertEquals(emptyList<CpuDetector.Cluster>(), detector.groupClusters(emptyList()))
    }

    // ------------------------------------------------------------- the seam itself

    @Test
    fun `the sysfs seam returns null for a path it does not have`() {
        val fs = FakeSysFs(mapOf("/sys/devices/system/cpu/present" to "0-7"))
        assertEquals("0-7", fs.read("/sys/devices/system/cpu/present"))
        assertNull(fs.read("/sys/devices/system/cpu/online"))
        assertTrue(fs.exists("/sys/devices/system/cpu/present"))
        assertTrue(!fs.exists("/sys/devices/system/cpu/online"))
    }

    private fun freq(cpu: Int, maxKhz: Long) =
        CpuDetector.CpuFreq(cpu, maxKhz, null, null, null)

    /** A [CpuDetector.SysFs] backed by a map, so a fixture stands in for the kernel. */
    private class FakeSysFs(private val files: Map<String, String>) : CpuDetector.SysFs {
        override fun read(path: String): String? = files[path]
        override fun exists(path: String): Boolean = files.containsKey(path)
    }

    private companion object {
        /**
         * A real arm64 `/proc/cpuinfo` header. Note what is *not* here: no model name and
         * no MHz. The kernel stopped exposing those on arm64, which is why the CPU lab
         * reports the implementer and part codes instead of a marketing name.
         */
        val ARM64_CPUINFO = """
            Processor	: AArch64 Processor rev 1 (aarch64)
            BogoMIPS	: 38.40
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp
            CPU implementer	: 0x41
            CPU part	: 0xd0b
        """.trimIndent()
    }
}
