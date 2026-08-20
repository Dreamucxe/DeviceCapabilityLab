package com.devicelab.data.detect

import com.devicelab.data.detect.FeaturesDetector.Companion.FEATURES
import com.devicelab.data.detect.FeaturesDetector.Companion.GROUPS
import com.devicelab.data.detect.FeaturesDetector.Companion.deqpDate
import com.devicelab.data.detect.FeaturesDetector.FeatureSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feature table and the deqp date decoder.
 *
 * The table is the one place in the app where a wrong number produces exactly the failure
 * the whole project exists to avoid. `hasSystemFeature("android.hardware.uwb")` returns
 * `false` on Android 9 -- not because the device lacks ultra-wideband, but because the
 * platform has never heard the name. The `since` value is what turns that `false` into
 * "Requires API 34+ — this device is running API 28". Set it too low and the app prints a
 * confident, false "unsupported"; the row looks entirely normal, which is what makes it
 * dangerous.
 *
 * These tests cannot check a `since` against the SDK -- that was done when the table was
 * generated, from the `data/api-versions.xml` beside `android.jar`. What they can check is
 * every structural property whose violation would make a row silently wrong or silently
 * invisible: a group id that matches no section, a duplicate constant, a level above the
 * compile SDK, a `versioned` flag that disagrees with the code that renders versions.
 */
class FeaturesDetectorTest {

    // -------------------------------------------------------------- deqp decoding

    /**
     * CTS packs a date into the level: year in the high sixteen bits, then month and day a
     * byte each. The values below are the ones AOSP documents for
     * `FEATURE_VULKAN_DEQP_LEVEL`, which is what makes them worth asserting -- they are
     * the encodings a real device actually reports.
     */
    @Test
    fun `a real deqp level decodes to the date it encodes`() {
        // 0x07E30301 — the level required of Android 11 devices.
        assertEquals("2019-03-01", deqpDate(132_317_953))
        // 0x07E40301 — Android 12.
        assertEquals("2020-03-01", deqpDate(132_383_489))
        // 0x07E50301 — Android 13.
        assertEquals("2021-03-01", deqpDate(132_449_025))
    }

    @Test
    fun `the packed fields are read from the right bits`() {
        assertEquals("2023-11-14", deqpDate((2023 shl 16) or (11 shl 8) or 14))
        assertEquals("2024-12-31", deqpDate((2024 shl 16) or (12 shl 8) or 31))
    }

    /** Rendered fixed-width, so a list of them lines up and sorts as text. */
    @Test
    fun `single digit months and days are zero padded`() {
        assertEquals("2022-01-05", deqpDate((2022 shl 16) or (1 shl 8) or 5))
    }

    /**
     * The plausibility gate. An encoding this decoder does not recognise must come back as
     * null so the caller prints the raw level -- inventing "0000-00-00" or "year 3" from a
     * number that plainly is not a date would be the fabrication this app refuses to do.
     */
    @Test
    fun `a number that is not a packed date is not decoded`() {
        assertNull("zero is not a date", deqpDate(0))
        assertNull("a bare level number is not a date", deqpDate(1))
        assertNull("a bare level number is not a date", deqpDate(7))
    }

    @Test
    fun `an implausible year is rejected`() {
        assertNull(deqpDate((1999 shl 16) or (3 shl 8) or 1))
        assertNull(deqpDate((2101 shl 16) or (3 shl 8) or 1))
    }

    @Test
    fun `the year bounds themselves are accepted`() {
        assertEquals("2000-01-01", deqpDate((2000 shl 16) or (1 shl 8) or 1))
        assertEquals("2100-12-31", deqpDate((2100 shl 16) or (12 shl 8) or 31))
    }

    @Test
    fun `an impossible month or day is rejected`() {
        assertNull("month zero", deqpDate((2020 shl 16) or (0 shl 8) or 1))
        assertNull("month thirteen", deqpDate((2020 shl 16) or (13 shl 8) or 1))
        assertNull("day zero", deqpDate((2020 shl 16) or (3 shl 8) or 0))
        assertNull("day thirty-two", deqpDate((2020 shl 16) or (3 shl 8) or 32))
    }

    /**
     * `FeatureInfo.version` is a signed int and the platform is not obliged to put a date
     * in it. A negative value shifts to a negative year, which the range check rejects
     * rather than formatting as something like "-0001-03-01".
     */
    @Test
    fun `a negative level is rejected rather than formatted`() {
        assertNull(deqpDate(-1))
        assertNull(deqpDate(Int.MIN_VALUE))
    }

    /**
     * Day-of-month is only range-checked, not calendar-checked. That is deliberate: the
     * value is a CTS milestone, not a date the app does arithmetic on, and a decoder that
     * rejected 2019-02-30 while accepting 2019-02-28 would be adding a rule the encoding
     * does not have. The test records the choice so it is not mistaken for an oversight.
     */
    @Test
    fun `a day the calendar lacks is still in range and still decoded`() {
        assertEquals("2019-02-30", deqpDate((2019 shl 16) or (2 shl 8) or 30))
    }

    // ------------------------------------------------------------ table integrity

    @Test
    fun `the table is not empty`() {
        assertTrue("the feature table carries the whole lab", FEATURES.size > 100)
    }

    /**
     * The one that would fail silently. `groupSection` selects rows by group id, so a typo
     * puts a feature in no section at all -- the row is built and then never rendered, and
     * nothing anywhere reports a problem.
     */
    @Test
    fun `every feature belongs to a group that exists`() {
        val known = GROUPS.mapTo(HashSet()) { it.id }
        val orphaned = FEATURES.filterNot { it.group in known }.map { "${it.constant}=${it.group}" }
        assertEquals("features whose group id matches no section", emptyList<String>(), orphaned)
    }

    @Test
    fun `every group has at least one feature`() {
        val used = FEATURES.mapTo(HashSet()) { it.group }
        val empty = GROUPS.filterNot { it.id in used }.map { it.id }
        assertEquals("groups that would render nothing", emptyList<String>(), empty)
    }

    @Test
    fun `group ids are unique`() {
        assertEquals(GROUPS.size, GROUPS.mapTo(HashSet()) { it.id }.size)
    }

    /** A duplicate would query the same flag twice and show two identical rows. */
    @Test
    fun `no constant appears twice`() {
        val duplicates = FEATURES.groupBy { it.constant }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), duplicates)
    }

    @Test
    fun `no feature string appears twice`() {
        val duplicates = FEATURES.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), duplicates)
    }

    /**
     * Labels are the only hand-written field, and they are what the user reads. Two rows
     * with the same label in the same section would be indistinguishable on screen.
     */
    @Test
    fun `labels are unique within a group`() {
        FEATURES.groupBy { it.group }.forEach { (group, specs) ->
            val duplicates = specs.groupBy { it.label }.filterValues { it.size > 1 }.keys
            assertEquals("duplicate labels in group $group", emptySet<String>(), duplicates)
        }
    }

    @Test
    fun `every row has a label a constant and a feature string`() {
        FEATURES.forEach { spec ->
            assertTrue("blank label for ${spec.constant}", spec.label.isNotBlank())
            assertTrue("blank name for ${spec.constant}", spec.name.isNotBlank())
            assertTrue(
                "${spec.constant} does not look like a PackageManager constant",
                spec.constant.startsWith("FEATURE_"),
            )
        }
    }

    /**
     * The feature strings are the platform's own, taken from the constants rather than
     * typed, and every one of them is reverse-DNS. A row whose name did not look like a
     * feature string would be a sign the constant reference had been replaced by a literal.
     */
    @Test
    fun `every feature string is a reverse dns name`() {
        FEATURES.forEach { spec ->
            assertTrue(
                "${spec.constant} = '${spec.name}' is not a dotted feature name",
                spec.name.contains('.') && spec.name.none { it.isWhitespace() },
            )
        }
        // Every `PackageManager.FEATURE_*` string in the compile SDK is under `android.`,
        // checked against android.jar rather than assumed. A name outside it would mean a
        // literal had been written where a constant reference belongs.
        val prefixes = FEATURES.mapTo(HashSet()) { it.name.substringBefore('.') }
        assertEquals(setOf("android"), prefixes)
    }

    /**
     * The gate has to be a real API level. Below 1 it could never fire; above the compile
     * SDK the constant could not have compiled, so a number larger than 34 would mean the
     * `since` was typed rather than read.
     */
    @Test
    fun `every gate is an api level this app could have compiled against`() {
        FEATURES.forEach { spec ->
            assertTrue(
                "${spec.constant} is gated at API ${spec.since}",
                spec.since in 1..34,
            )
        }
    }

    /**
     * A gate at or below minSdk 26 can never fire, which is fine and expected -- most
     * feature constants are far older than this app's floor. What matters is that the
     * newer ones are gated at all, because those are the rows where an ungated `false`
     * would be a fabricated "unsupported".
     */
    @Test
    fun `the features introduced after minSdk are the ones carrying real gates`() {
        val gated = FEATURES.filter { it.since > 26 }
        assertTrue("nothing is gated above minSdk", gated.size > 30)
        // Spot-check the row that motivates the whole mechanism: UWB's constant did not
        // exist until API 34, so on any device below that the answer is "not exposed".
        val uwb = FEATURES.single { it.constant == "FEATURE_UWB" }
        assertEquals(34, uwb.since)
        assertEquals("android.hardware.uwb", uwb.name)
    }

    /**
     * `describeVersion` renders `FeatureInfo.version` only for rows marked `versioned`, and
     * it special-cases nine constants by name. A constant special-cased but not marked
     * would take the "Present" path and the special case would be dead code; a constant
     * marked but not special-cased falls to the generic "version N", which is correct but
     * only meaningful when the number means something.
     */
    @Test
    fun `every specially rendered constant is marked as versioned`() {
        val speciallyRendered = setOf(
            "FEATURE_VULKAN_HARDWARE_VERSION",
            "FEATURE_VULKAN_HARDWARE_LEVEL",
            "FEATURE_VULKAN_HARDWARE_COMPUTE",
            "FEATURE_VULKAN_DEQP_LEVEL",
            "FEATURE_OPENGLES_DEQP_LEVEL",
            "FEATURE_HARDWARE_KEYSTORE",
            "FEATURE_STRONGBOX_KEYSTORE",
        )
        val present = FEATURES.filter { it.constant in speciallyRendered }
        assertEquals(
            "every specially rendered constant should be in the table",
            speciallyRendered,
            present.mapTo(HashSet()) { it.constant },
        )
        val unmarked = present.filterNot { it.versioned }.map { it.constant }
        assertEquals("special-cased but not versioned", emptyList<String>(), unmarked)
    }

    /** Search is how a user finds a flag among a hundred and forty of them. */
    @Test
    fun `every row carries at least one search term`() {
        val bare = FEATURES.filter { it.searchTerms.isEmpty() }.map { it.constant }
        assertEquals(emptyList<String>(), bare)
    }

    @Test
    fun `search terms are lowercase so the query does not have to match case`() {
        FEATURES.forEach { spec ->
            spec.searchTerms.forEach { term ->
                assertEquals(
                    "${spec.constant} search term '$term' is not lowercase",
                    term.lowercase(),
                    term,
                )
            }
            assertTrue(
                "${spec.constant} has a blank search term",
                spec.searchTerms.none { it.isBlank() },
            )
        }
    }

    /**
     * The queries Section 21 names by example have to reach a row. These are the ones this
     * lab is responsible for; the codec and display labs answer "AV1" and "120Hz".
     */
    @Test
    fun `the documented example searches reach a feature row`() {
        assertTrue("vulkan", FEATURES.any { matches(it, "vulkan") })
        assertTrue("gyroscope", FEATURES.any { matches(it, "gyroscope") })
        assertTrue("raw", FEATURES.any { matches(it, "raw") })
        assertTrue("wifi", FEATURES.any { matches(it, "wifi") })
        assertTrue("fingerprint", FEATURES.any { matches(it, "fingerprint") })
        assertTrue("nfc", FEATURES.any { matches(it, "nfc") })
        assertTrue("uwb", FEATURES.any { matches(it, "uwb") })
        assertTrue("strongbox", FEATURES.any { matches(it, "strongbox") })
    }

    /**
     * Every group the report renders should be reachable by name too, since the group
     * titles are what a user scanning the Hardware tab actually sees.
     */
    @Test
    fun `the groups cover the hardware areas the labs also report on`() {
        val ids = GROUPS.mapTo(HashSet()) { it.id }
        listOf(
            "camera", "sensors", "biometrics", "telephony", "radio",
            "nfc", "location", "audio", "graphics", "security", "usb",
        ).forEach { assertTrue("missing group $it", it in ids) }
    }

    private fun matches(spec: FeatureSpec, query: String): Boolean {
        val q = query.lowercase()
        return spec.label.lowercase().contains(q) ||
            spec.name.lowercase().contains(q) ||
            spec.constant.lowercase().contains(q) ||
            spec.searchTerms.any { it.contains(q) }
    }
}
