package com.devicelab.core.common

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timestamp formatting.
 *
 * [Timestamps.iso] is for the machine and must be UTC regardless of where the device is,
 * because an export whose timestamp silently meant "wherever that phone happened to be"
 * cannot be compared with another. That is the property most of these tests defend.
 */
class TimestampsTest {

    @Test
    fun `iso renders a known instant in utc`() {
        // 2023-11-14T22:13:20Z
        assertEquals("2023-11-14T22:13:20Z", Timestamps.iso(1_700_000_000_000L))
    }

    @Test
    fun `iso is unaffected by the device time zone`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val plus14 = Timestamps.iso(1_700_000_000_000L)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Niue"))
            val minus11 = Timestamps.iso(1_700_000_000_000L)
            assertEquals(plus14, minus11)
            assertEquals("2023-11-14T22:13:20Z", plus14)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `iso is unaffected by the device locale`() {
        val previous = Locale.getDefault()
        try {
            // A locale with a non-Gregorian calendar and non-ASCII digits would
            // otherwise reach the formatter.
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai-ca-buddhist"))
            assertEquals("2023-11-14T22:13:20Z", Timestamps.iso(1_700_000_000_000L))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `iso always ends in the zulu marker`() {
        assertTrue(Timestamps.iso(0L).endsWith("Z"))
        assertEquals("1970-01-01T00:00:00Z", Timestamps.iso(0L))
    }

    /** The person's format is local, because a scan taken this morning should say so. */
    @Test
    fun `readable is local time and human shaped`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("14 Nov 2023, 22:13", Timestamps.readable(1_700_000_000_000L))
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            assertEquals("14 Nov 2023, 23:13", Timestamps.readable(1_700_000_000_000L))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `the filename stamp is sortable and contains no path characters`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val stamp = Timestamps.forFilename(1_700_000_000_000L)
            assertEquals("20231114-221320", stamp)
            assertTrue(stamp.none { it in "/\\:*?\"<>| " })
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `durations use the smallest unit that reads sensibly`() {
        assertEquals("0 ms", Timestamps.duration(0))
        assertEquals("999 ms", Timestamps.duration(999))
        assertEquals("1.0 s", Timestamps.duration(1_000))
        assertEquals("2.5 s", Timestamps.duration(2_500))
        assertEquals("59.9 s", Timestamps.duration(59_900))
        assertEquals("1 min 0 s", Timestamps.duration(60_000))
        assertEquals("1 min 30 s", Timestamps.duration(90_000))
        assertEquals("2 min 5 s", Timestamps.duration(125_000))
    }

    @Test
    fun `durations are locale independent`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("2.5 s", Timestamps.duration(2_500))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
