package com.devicelab.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five-state support model and the fact tree.
 *
 * These tests exist because the whole app's honesty rests on [Support] keeping five
 * distinct states and on [Fact.hasValue] telling a measurement apart from an absence.
 * A refactor that collapsed [Support.NOT_EXPOSED] into [Support.UNSUPPORTED] would
 * compile, would look fine on screen, and would make every older device appear to lack
 * hardware it has.
 */
class FactTest {

    @Test
    fun `every support state has a distinct glyph and label`() {
        val glyphs = Support.entries.map { it.glyph }
        val labels = Support.entries.map { it.label }
        assertEquals("glyphs must be distinguishable", glyphs.size, glyphs.toSet().size)
        assertEquals("labels must be distinguishable", labels.size, labels.toSet().size)
        assertTrue("no glyph may be blank", glyphs.none { it.isBlank() })
    }

    @Test
    fun `only supported and partial count as affirmative`() {
        assertTrue(Support.SUPPORTED.isAffirmative)
        assertTrue(Support.PARTIAL.isAffirmative)
        assertFalse(Support.UNSUPPORTED.isAffirmative)
        assertFalse(Support.NOT_EXPOSED.isAffirmative)
        assertFalse(Support.UNKNOWN.isAffirmative)
        assertFalse(Support.INFORMATIONAL.isAffirmative)
    }

    @Test
    fun `hasValue is false for every absence string and true for a measurement`() {
        assertFalse(fact("A", Absent.NOT_EXPOSED).hasValue)
        assertFalse(fact("B", Absent.UNKNOWN).hasValue)
        assertFalse(fact("C", Absent.UNAVAILABLE).hasValue)
        assertTrue(fact("D", "120 Hz").hasValue)
    }

    /**
     * "None" is a real answer, not an absence. A device with no HDR types genuinely
     * reports an empty list, which is different from a device that could not be asked.
     */
    @Test
    fun `None counts as a value because it is an answer`() {
        assertTrue(fact("HDR types", Absent.NONE).hasValue)
    }

    @Test
    fun `matches searches label value detail api and synonyms`() {
        val subject = Fact(
            label = "Wi-Fi standard",
            value = "802.11ax",
            provenance = Provenance.Queried("WifiManager.isWifiStandardSupported()"),
            detail = "Reported for the 5 GHz band",
            searchTerms = listOf("Wi-Fi 6", "wifi6"),
        )
        assertTrue("label", subject.matches("standard"))
        assertTrue("value", subject.matches("802.11"))
        assertTrue("detail", subject.matches("5 GHz"))
        assertTrue("api name", subject.matches("WifiManager"))
        assertTrue("synonym", subject.matches("wi-fi 6"))
        assertTrue("case insensitive", subject.matches("WI-FI 6"))
        assertFalse(subject.matches("bluetooth"))
    }

    @Test
    fun `a blank query matches everything`() {
        assertTrue(fact("A", "1").matches(""))
        assertTrue(fact("A", "1").matches("   "))
    }

    @Test
    fun `flatten produces one dotted path per fact including children`() {
        val paths = tree().flatten().map { it.first }
        assertEquals(
            listOf(
                "root.Top",
                "root.camera0.Hardware level",
                "root.camera1.Hardware level",
            ),
            paths,
        )
    }

    /**
     * Two cameras both reporting "Hardware level" must not collide. The path prefix is
     * what keeps them apart, and it is what the snapshot key is built from.
     */
    @Test
    fun `identical labels in sibling sections get distinct paths`() {
        val paths = tree().flatten().map { it.first }
        assertEquals(paths.size, paths.toSet().size)
    }

    @Test
    fun `allFacts walks the whole subtree`() {
        assertEquals(3, tree().allFacts().size)
    }

    @Test
    fun `fact lookup searches children`() {
        assertNotNull(tree().fact("Hardware level"))
        assertNull(tree().fact("Nothing named this"))
    }

    @Test
    fun `filtered keeps only matching facts and drops emptied children`() {
        val filtered = tree().filtered("LIMITED")
        assertNotNull(filtered)
        assertEquals(0, filtered!!.facts.size)
        assertEquals(1, filtered.children.size)
        assertEquals("camera0", filtered.children.single().id)
    }

    @Test
    fun `filtered returns null when nothing in the subtree matches`() {
        assertNull(tree().filtered("no such capability anywhere"))
    }

    /** A title hit keeps the whole section, so searching "Camera" shows all of it. */
    @Test
    fun `a section title hit keeps every fact under it`() {
        val filtered = tree().children.first().filtered("Camera 0")
        assertEquals(1, filtered?.facts?.size)
    }

    @Test
    fun `a blank query returns the section unchanged`() {
        val subject = tree()
        assertEquals(subject, subject.filtered(""))
    }

    private fun tree() = Section(
        id = "root",
        title = "Cameras",
        facts = listOf(fact("Top", "value")),
        children = listOf(
            Section("camera0", "Camera 0", facts = listOf(fact("Hardware level", "LIMITED"))),
            Section("camera1", "Camera 1", facts = listOf(fact("Hardware level", "LEGACY"))),
        ),
    )

    private fun fact(label: String, value: String) =
        Fact(label, value, Provenance.Queried("test"))
}
