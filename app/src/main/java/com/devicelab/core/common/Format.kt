package com.devicelab.core.common

import java.util.Locale

/**
 * Value formatting shared by every lab and all three export formats, so that a
 * figure reads identically on screen and in a report.
 */
object Format {

    /**
     * Storage and RAM in binary units.
     *
     * Deliberately GiB rather than the decimal GB that storage marketing uses:
     * every figure here comes from a platform API that counts bytes, and the
     * suffix should match what was actually counted.
     */
    fun bytes(value: Long): String {
        if (value < 0) return "Unknown"
        if (value < 1024) return "$value B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var v = value.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return if (v >= 100) {
            String.format(Locale.US, "%.0f %s", v, units[i])
        } else {
            String.format(Locale.US, "%.1f %s", v, units[i])
        }
    }

    fun hertz(value: Float): String =
        if (value % 1f == 0f) {
            String.format(Locale.US, "%.0f Hz", value)
        } else {
            String.format(Locale.US, "%.2f Hz", value)
        }

    fun kilohertz(sampleRateHz: Int): String {
        val khz = sampleRateHz / 1000.0
        return if (khz % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f kHz", khz)
        } else {
            String.format(Locale.US, "%.1f kHz", khz)
        }
    }

    fun decimal(value: Float, places: Int = 2): String =
        String.format(Locale.US, "%.${places}f", value)

    fun resolution(width: Int, height: Int): String = "$width × $height"

    fun megapixels(width: Int, height: Int): String =
        String.format(Locale.US, "%.1f MP", width.toLong() * height / 1_000_000.0)

    fun percent(fraction: Double): String =
        String.format(Locale.US, "%.0f%%", (fraction * 100).coerceIn(0.0, 100.0))

    /** "1.2.3" from a packed Vulkan version integer. */
    fun vulkanVersion(packed: Int): String {
        val major = (packed ushr 22) and 0x7F
        val minor = (packed ushr 12) and 0x3FF
        val patch = packed and 0xFFF
        return "$major.$minor.$patch"
    }

    /** Joins a list for display, or returns the honest empty text. */
    fun list(values: Collection<String>, empty: String = "None"): String =
        if (values.isEmpty()) empty else values.joinToString(", ")

    fun titleCaseEnum(name: String): String =
        name.split('_').joinToString(" ") { part ->
            if (part.length <= 3 && part.all { it.isUpperCase() }) {
                part
            } else {
                part.lowercase().replaceFirstChar { it.uppercase() }
            }
        }

    /** Micro-amps as the sensor API reports them, in the unit it uses. */
    fun milliamps(value: Float): String = String.format(Locale.US, "%.3f mA", value)

    /**
     * A codec bitrate.
     *
     * Decimal Mbit/s here, unlike [bytes], because that is the unit codecs and
     * broadcasters quote and the API's own value is a count of bits per second.
     */
    fun bitrate(bitsPerSecond: Long): String = when {
        bitsPerSecond <= 0 -> "Unknown"
        bitsPerSecond >= 1_000_000 ->
            String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        bitsPerSecond >= 1_000 -> String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1_000.0)
        else -> "$bitsPerSecond bps"
    }
}
