package com.devicelab.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Value formatting.
 *
 * Formatting is where an honest figure can still mislead. The unit has to match what
 * the API actually counted -- binary units for a byte count, decimal for a bitrate --
 * and the locale has to be fixed, or a device set to a comma-decimal locale would
 * produce an export that reads as a different number elsewhere.
 */
class FormatTest {

    @Test
    fun `bytes below a kibibyte are exact`() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1023 B", Format.bytes(1023))
    }

    @Test
    fun `bytes step through binary units at the right boundaries`() {
        assertEquals("1.0 KiB", Format.bytes(1024))
        assertEquals("1.0 MiB", Format.bytes(1024L * 1024))
        assertEquals("1.0 GiB", Format.bytes(1024L * 1024 * 1024))
        assertEquals("1.0 TiB", Format.bytes(1024L * 1024 * 1024 * 1024))
    }

    /** Binary, not decimal: the figure came from an API that counted bytes. */
    @Test
    fun `bytes uses binary units so the suffix matches what was counted`() {
        assertEquals("7.5 GiB", Format.bytes(8_053_063_680L))
        assertTrue(Format.bytes(1024).endsWith("KiB"))
    }

    @Test
    fun `bytes drops the decimal once the figure reaches three digits`() {
        assertEquals("100 KiB", Format.bytes(1024L * 100))
        assertEquals("99.0 KiB", Format.bytes(1024L * 99))
    }

    @Test
    fun `bytes stays in tebibytes rather than inventing a larger unit`() {
        assertTrue(Format.bytes(1024L * 1024 * 1024 * 1024 * 5).endsWith("TiB"))
    }

    @Test
    fun `a negative byte count is unknown rather than a negative size`() {
        assertEquals("Unknown", Format.bytes(-1))
    }

    @Test
    fun `hertz keeps the fraction only when there is one`() {
        assertEquals("60 Hz", Format.hertz(60f))
        assertEquals("120 Hz", Format.hertz(120f))
        assertEquals("59.94 Hz", Format.hertz(59.94f))
    }

    @Test
    fun `kilohertz keeps the fraction only when there is one`() {
        assertEquals("48 kHz", Format.kilohertz(48_000))
        assertEquals("44.1 kHz", Format.kilohertz(44_100))
        assertEquals("192 kHz", Format.kilohertz(192_000))
    }

    @Test
    fun `resolution uses a multiplication sign not the letter x`() {
        assertEquals("1080 × 2400", Format.resolution(1080, 2400))
    }

    @Test
    fun `megapixels are computed without integer overflow`() {
        assertEquals("12.2 MP", Format.megapixels(4032, 3024))
        // 8000 x 6000 overflows a 32-bit multiply; the Long widening is what saves it.
        assertEquals("48.0 MP", Format.megapixels(8000, 6000))
    }

    @Test
    fun `percent is clamped to a sensible range`() {
        assertEquals("0%", Format.percent(0.0))
        assertEquals("50%", Format.percent(0.5))
        assertEquals("100%", Format.percent(1.0))
        assertEquals("100%", Format.percent(1.4))
        assertEquals("0%", Format.percent(-0.2))
    }

    /** The packed layout is Vulkan's own: 7 bits major, 10 minor, 12 patch. */
    @Test
    fun `vulkan versions unpack to major minor patch`() {
        assertEquals("1.1.0", Format.vulkanVersion((1 shl 22) or (1 shl 12)))
        assertEquals("1.3.128", Format.vulkanVersion((1 shl 22) or (3 shl 12) or 128))
        assertEquals("0.0.0", Format.vulkanVersion(0))
    }

    @Test
    fun `list joins values and names the empty case honestly`() {
        assertEquals("a, b, c", Format.list(listOf("a", "b", "c")))
        assertEquals("None", Format.list(emptyList()))
        assertEquals("Not exposed", Format.list(emptyList(), empty = "Not exposed"))
    }

    /**
     * Platform constants are SHOUTED, and reading a screen of them is unpleasant. But a
     * short all-caps word is far more likely to be an acronym than a word -- RAW, AAC,
     * PCM, TEE -- and "Raw" or "Aac" would be worse than leaving it alone, so segments of
     * three characters or fewer keep their case.
     */
    @Test
    fun `enum names become readable while short acronyms keep their case`() {
        assertEquals("Level 3", Format.titleCaseEnum("LEVEL_3"))
        assertEquals("Hdr10 Plus", Format.titleCaseEnum("HDR10_PLUS"))
        assertEquals("RAW Sensor", Format.titleCaseEnum("RAW_SENSOR"))
        assertEquals("Hardware Level 3", Format.titleCaseEnum("HARDWARE_LEVEL_3"))
        // Three letters, so it is left as-is -- the acronym rule cannot tell OFF from AAC.
        assertEquals("OFF", Format.titleCaseEnum("OFF"))
        assertEquals("Offset", Format.titleCaseEnum("OFFSET"))
    }

    @Test
    fun `decimal honours the requested precision`() {
        assertEquals("1.50", Format.decimal(1.5f))
        assertEquals("1.5", Format.decimal(1.5f, places = 1))
        assertEquals("2", Format.decimal(1.5f, places = 0))
    }

    @Test
    fun `milliamps keep three places because sensor draws are small`() {
        assertEquals("0.130 mA", Format.milliamps(0.13f))
        assertEquals("12.500 mA", Format.milliamps(12.5f))
    }

    /** Decimal, unlike bytes: bitrate APIs count bits per second, as do broadcasters. */
    @Test
    fun `bitrate scales decimally and names its unit`() {
        assertEquals("500 bps", Format.bitrate(500))
        assertEquals("1 kbps", Format.bitrate(1_000))
        assertEquals("1.0 Mbps", Format.bitrate(1_000_000))
        assertEquals("120.0 Mbps", Format.bitrate(120_000_000))
    }

    @Test
    fun `a non positive bitrate is unknown rather than zero`() {
        assertEquals("Unknown", Format.bitrate(0))
        assertEquals("Unknown", Format.bitrate(-1))
    }

    /**
     * Every figure uses an explicit US locale. Without it, a device set to a
     * comma-decimal locale would export "59,94 Hz", which reads as a different number
     * to anything parsing it elsewhere.
     */
    @Test
    fun `formatting is locale independent`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("59.94 Hz", Format.hertz(59.94f))
            assertEquals("7.5 GiB", Format.bytes(8_053_063_680L))
            assertEquals("1.0 Mbps", Format.bitrate(1_000_000))
            assertEquals("12.2 MP", Format.megapixels(4032, 3024))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
