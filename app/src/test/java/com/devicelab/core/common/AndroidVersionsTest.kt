package com.devicelab.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API-level to version-name table.
 *
 * There is no platform API that maps an API level to a marketing name, so this table is
 * hand-maintained -- which makes the unknown-level behaviour the interesting case. A
 * device running a level this build has never heard of must report the bare number rather
 * than the nearest name it knows, because "Android 16" on an API 40 device would be a
 * fabrication of exactly the kind the brief forbids.
 */
class AndroidVersionsTest {

    @Test
    fun `known levels get their marketing name`() {
        assertEquals("Android 8.0 Oreo", AndroidVersions.name(26))
        assertEquals("Android 9 Pie", AndroidVersions.name(28))
        assertEquals("Android 12L", AndroidVersions.name(32))
        assertEquals("Android 14", AndroidVersions.name(34))
    }

    @Test
    fun `an unknown level is reported as the bare api level`() {
        assertEquals("API 99", AndroidVersions.name(99))
        assertEquals("API 0", AndroidVersions.name(0))
        assertEquals("API 20", AndroidVersions.name(20))
    }

    @Test
    fun `describe pairs the name with the level`() {
        assertEquals("Android 14 (API 34)", AndroidVersions.describe("14", 34))
        assertEquals("Android 9 Pie (API 28)", AndroidVersions.describe("9", 28))
    }

    /**
     * When the table has no entry, the release string the device reported is used
     * instead. That value came from `Build.VERSION.RELEASE`, so it is still the device's
     * own answer rather than this app's guess.
     */
    @Test
    fun `describe falls back to the release the device reported`() {
        assertEquals("Android 17 (API 99)", AndroidVersions.describe("17", 99))
    }

    @Test
    fun `a requirement names both the level and the version`() {
        assertEquals("API 31+ (Android 12)", AndroidVersions.requirement(31))
        assertEquals("API 26+ (Android 8.0 Oreo)", AndroidVersions.requirement(26))
        // Unknown levels still read sensibly, if less helpfully.
        assertEquals("API 99+ (API 99)", AndroidVersions.requirement(99))
    }

    /** Every level this app can run on must be nameable, or a row would read "API 26". */
    @Test
    fun `every supported api level has a name`() {
        (26..36).forEach { level ->
            assertTrue(
                "API $level must be in the table",
                AndroidVersions.name(level).startsWith("Android "),
            )
        }
    }

    @Test
    fun `no name is a duplicate of another`() {
        val names = (21..36).map { AndroidVersions.name(it) }
        // 5.0/5.1, 7.0/7.1 and 8.0/8.1 differ by their point release, so all distinct.
        assertEquals(names.size, names.toSet().size)
        assertFalse(names.any { it.isBlank() })
    }
}
