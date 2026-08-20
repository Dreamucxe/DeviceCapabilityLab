package com.devicelab.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Timestamp formatting for snapshot names, the history list and exports.
 *
 * `java.time` is used directly rather than through desugaring: minSdk is 26 and the
 * whole package landed in the platform at exactly that level, so every supported
 * device has it.
 *
 * The two formats exist for two different readers. [iso] is for the machine -- an
 * export consumed by a script, sorted or parsed elsewhere -- and is always UTC with an
 * explicit offset, because an export whose timestamp silently meant "wherever that
 * device happened to be" cannot be compared with another. [readable] is for the
 * person, and is local time, because a scan they took this morning should say so.
 */
object Timestamps {

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val READABLE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US)
    private val FILENAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

    fun iso(millis: Long): String =
        ISO.format(Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")))

    fun readable(millis: Long): String =
        READABLE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** A filename-safe local stamp, for the export file name. */
    fun forFilename(millis: Long): String =
        FILENAME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /**
     * A duration in the smallest unit that reads sensibly.
     *
     * Used for the scan duration on the dashboard, which is a measurement of this app
     * and not of the device -- it is labelled as such wherever it appears, so nobody
     * mistakes it for a benchmark.
     */
    fun duration(millis: Long): String = when {
        millis < 1_000 -> "$millis ms"
        millis < 60_000 -> String.format(Locale.US, "%.1f s", millis / 1_000.0)
        else -> "${millis / 60_000} min ${(millis % 60_000) / 1_000} s"
    }
}
