package com.devicelab.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot comparison engine.
 *
 * Section 19 requires all four outcomes -- ADDED, REMOVED, CHANGED, UNCHANGED -- and
 * the comparison is keyed rather than positional so that a new section in a later app
 * version cannot shift every subsequent row and report a device full of changes that
 * did not happen. That property is the main thing pinned here.
 */
class SnapshotDiffTest {

    @Test
    fun `an identical snapshot compared with itself is entirely unchanged`() {
        val subject = snapshot(1, rows = listOf(row("a", "1"), row("b", "2")))
        val diff = SnapshotComparer.compare(subject, subject)
        assertEquals(2, diff.unchanged)
        assertEquals(0, diff.added)
        assertEquals(0, diff.removed)
        assertEquals(0, diff.changed)
        assertFalse(diff.hasDifferences)
        assertEquals("No differences", diff.headline)
    }

    @Test
    fun `a row only on the right is added and a row only on the left is removed`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = listOf(row("shared", "1"), row("gone", "x"))),
            snapshot(2, rows = listOf(row("shared", "1"), row("fresh", "y"))),
        )
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(1, diff.unchanged)
        assertEquals("fresh", diff.of(ChangeKind.ADDED).single().label)
        assertEquals("gone", diff.of(ChangeKind.REMOVED).single().label)
    }

    /**
     * Keyed, not positional. Inserting a row at the front of the newer snapshot must
     * produce exactly one ADDED and no CHANGED rows -- a positional diff would report
     * every following row as changed.
     */
    @Test
    fun `inserting a row does not shift the rows after it`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = listOf(row("a", "1"), row("b", "2"), row("c", "3"))),
            snapshot(2, rows = listOf(row("new", "0"), row("a", "1"), row("b", "2"), row("c", "3"))),
        )
        assertEquals(1, diff.added)
        assertEquals(0, diff.changed)
        assertEquals(0, diff.removed)
        assertEquals(3, diff.unchanged)
    }

    @Test
    fun `a changed value reports both sides in the summary`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = listOf(row("Refresh rate", "60 Hz"))),
            snapshot(2, rows = listOf(row("Refresh rate", "120 Hz"))),
        )
        val delta = diff.of(ChangeKind.CHANGED).single()
        assertEquals("60 Hz → 120 Hz", delta.summary)
        assertEquals("value changed", delta.reason)
    }

    @Test
    fun `a changed verdict at an unchanged value is reported as a status change`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = listOf(row("Vulkan", "Supported", Support.UNKNOWN))),
            snapshot(2, rows = listOf(row("Vulkan", "Supported", Support.SUPPORTED))),
        )
        val delta = diff.of(ChangeKind.CHANGED).single()
        assertEquals("Unknown → Fully supported", delta.summary)
        assertEquals(
            "status changed from Unknown to Fully supported",
            delta.reason,
        )
    }

    /**
     * The subtle case. Same value, same verdict, but the device moved to an Android
     * version that can finally answer -- a real change, and one a value-only diff
     * would miss entirely. The row must explain itself or it reads as a bug.
     */
    @Test
    fun `a provenance-only change is reported and explained`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = listOf(row("HDR", "Supported", Support.SUPPORTED, "requires-api"))),
            snapshot(2, rows = listOf(row("HDR", "Supported", Support.SUPPORTED, "queried"))),
        )
        val delta = diff.of(ChangeKind.CHANGED).single()
        assertEquals("requires-api → queried", delta.summary)
        assertEquals(
            "how it was obtained changed from requires-api to queried",
            delta.reason,
        )
    }

    @Test
    fun `unchanged rows carry no reason`() {
        val subject = snapshot(1, rows = listOf(row("a", "1")))
        val delta = SnapshotComparer.compare(subject, subject).deltas.single()
        assertEquals(ChangeKind.UNCHANGED, delta.kind)
        assertNull(delta.reason)
        assertEquals("1", delta.summary)
    }

    @Test
    fun `presentation order follows the newer snapshot with disappearances appended`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = listOf(row("old-only", "x"), row("b", "2"))),
            snapshot(2, rows = listOf(row("b", "2"), row("c", "3"))),
        )
        assertEquals(listOf("b", "c", "old-only"), diff.deltas.map { it.label })
    }

    @Test
    fun `deltas group by lab in declaration order skipping empty labs`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = emptyList()),
            snapshot(
                2,
                rows = listOf(
                    row("gpu", "x", labId = Lab.GRAPHICS.id),
                    row("panel", "y", labId = Lab.DISPLAY.id),
                ),
            ),
        )
        val grouped = diff.byLab(setOf(ChangeKind.ADDED))
        assertEquals(listOf(Lab.DISPLAY, Lab.GRAPHICS), grouped.map { it.first })
    }

    @Test
    fun `an unknown lab id yields a null lab rather than throwing`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = emptyList()),
            snapshot(2, rows = listOf(row("x", "1", labId = "a-lab-from-a-later-version"))),
        )
        assertNull(diff.deltas.single().lab)
    }

    @Test
    fun `two scans of the same hardware are recognised as the same device`() {
        val diff = SnapshotComparer.compare(snapshot(1), snapshot(2))
        assertTrue(diff.sameDevice)
    }

    @Test
    fun `different hardware is named in the headline`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, model = "Pixel 6"),
            snapshot(2, model = "Galaxy S23"),
        )
        assertFalse(diff.sameDevice)
        assertEquals("Different devices: Google Pixel 6 vs Google Galaxy S23", diff.headline)
    }

    /**
     * An OS upgrade is by far the commonest reason a capability appears, so the
     * headline must say so before the reader concludes the hardware changed.
     */
    @Test
    fun `an api level change leads the headline`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, apiLevel = 33, rows = listOf(row("a", "1"))),
            snapshot(2, apiLevel = 34, rows = listOf(row("a", "2"))),
        )
        assertEquals("Same device, API 33 → API 34", diff.headline)
    }

    @Test
    fun `the headline counts changes when device and api are the same`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = listOf(row("a", "1"), row("gone", "x"))),
            snapshot(2, rows = listOf(row("a", "2"), row("new", "y"))),
        )
        assertEquals("1 changed, 1 added, 1 removed", diff.headline)
    }

    @Test
    fun `delta search covers the label the section and the summary`() {
        val diff = SnapshotComparer.compare(
            snapshot(1, rows = listOf(row("Refresh rate", "60 Hz"))),
            snapshot(2, rows = listOf(row("Refresh rate", "120 Hz"))),
        )
        val delta = diff.deltas.single()
        assertTrue(delta.matches("refresh"))
        assertTrue(delta.matches("Panel"))
        assertTrue(delta.matches("120"))
        assertTrue(delta.matches(""))
        assertFalse(delta.matches("bluetooth"))
    }

    @Test
    fun `rowsFor filters a snapshot to one lab`() {
        val subject = snapshot(
            1,
            rows = listOf(
                row("a", "1", labId = Lab.DISPLAY.id),
                row("b", "2", labId = Lab.CAMERA.id),
            ),
        )
        assertEquals(1, subject.rowsFor(Lab.DISPLAY).size)
        assertEquals(0, subject.rowsFor(Lab.AUDIO).size)
    }

    /** The History tab must roll up by exactly the rule the Dashboard uses. */
    @Test
    fun `a stored snapshot rolls up by the same rule as a live scan`() {
        val subject = snapshot(
            1,
            rows = listOf(
                row("a", "1", Support.SUPPORTED, domain = Domain.DISPLAY),
                row("b", "2", Support.UNSUPPORTED, domain = Domain.DISPLAY),
            ),
        )
        val display = subject.scorecard.single { it.domain == Domain.DISPLAY }
        assertEquals(Support.PARTIAL, display.support)
        assertEquals(1, display.supported)
        assertEquals(2, display.total)
    }

    @Test
    fun `a model that already names the manufacturer is not repeated`() {
        assertEquals(
            "Google Pixel 8",
            snapshot(1, manufacturer = "Google", model = "Google Pixel 8").deviceLabel,
        )
        assertEquals(
            "Google Pixel 8",
            snapshot(1, manufacturer = "Google", model = "Pixel 8").deviceLabel,
        )
    }

    @Test
    fun `the platform label carries both the release and the level`() {
        assertEquals("Android 14 (API 34)", snapshot(1).platformLabel)
    }

    @Test
    fun `sameAs ignores fields that are not part of the comparison`() {
        val left = row("a", "1").copy(detail = "one detail", provenance = "long explanation")
        val right = row("a", "1").copy(detail = "another", provenance = "reworded")
        assertTrue("wording changes are not device changes", left.sameAs(right))
    }

    @Test
    fun `keyOf composes a unique key from lab path and label`() {
        assertNotNull(FactRow.SEPARATOR)
        assertEquals(
            "display" + FactRow.SEPARATOR + "panel/modes" + FactRow.SEPARATOR + "Refresh rate",
            FactRow.keyOf("display", "panel/modes", "Refresh rate"),
        )
    }

    private fun snapshot(
        id: Long,
        manufacturer: String = "Google",
        model: String = "Pixel 6",
        apiLevel: Int = 34,
        rows: List<FactRow> = listOf(row("a", "1")),
    ) = Snapshot(
        id = id,
        name = "snapshot $id",
        capturedAtMillis = 1_700_000_000_000L + id,
        manufacturer = manufacturer,
        model = model,
        device = "device",
        androidRelease = "14",
        apiLevel = apiLevel,
        fingerprint = "fingerprint",
        rows = rows,
    )

    private fun row(
        label: String,
        value: String,
        support: Support = Support.INFORMATIONAL,
        provenanceKind: String = "queried",
        labId: String = Lab.DISPLAY.id,
        domain: Domain? = null,
    ) = FactRow(
        key = FactRow.keyOf(labId, "panel", label),
        labId = labId,
        sectionPath = "panel",
        sectionTitle = "Panel",
        label = label,
        value = value,
        support = support,
        provenanceKind = provenanceKind,
        provenance = "Queried · test",
        domain = domain,
    )
}
