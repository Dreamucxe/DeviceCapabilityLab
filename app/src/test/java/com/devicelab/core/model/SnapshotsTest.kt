package com.devicelab.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flattening a live profile into storable rows.
 *
 * The key-collision behaviour is the reason this has its own test. If two facts
 * collapsed onto one key, the lost row would appear as REMOVED in the very next
 * comparison -- a false report about the device, produced entirely by the app.
 */
class SnapshotsTest {

    @Test
    fun `every fact in every lab becomes exactly one row`() {
        assertEquals(4, Snapshots.rowsOf(profile()).size)
    }

    @Test
    fun `keys are unique across the whole profile`() {
        val keys = Snapshots.rowsOf(profile()).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `nested section paths are slash separated from the root section`() {
        val row = Snapshots.rowsOf(profile()).first { it.label == "Hardware level" }
        assertEquals("cameras/camera0", row.sectionPath)
        assertEquals("Camera 0", row.sectionTitle)
    }

    /**
     * A detector that emits the same label twice in one section is a bug, but a
     * silently dropped row is worse than a suffixed one: the suffix is visible, the
     * loss is not.
     */
    @Test
    fun `a duplicate label in one section is suffixed rather than dropped`() {
        val duplicated = CapabilityProfile(
            capturedAtMillis = 0L,
            reports = listOf(
                LabReport(
                    lab = Lab.DISPLAY,
                    sections = listOf(
                        Section(
                            id = "panel",
                            title = "Panel",
                            facts = listOf(fact("Mode", "a"), fact("Mode", "b"), fact("Mode", "c")),
                        )
                    ),
                )
            ),
        )
        val rows = Snapshots.rowsOf(duplicated)
        assertEquals(3, rows.size)
        assertEquals(3, rows.map { it.key }.toSet().size)
        assertTrue(rows[1].key.endsWith(" #2"))
        assertTrue(rows[2].key.endsWith(" #3"))
        assertEquals(listOf("a", "b", "c"), rows.map { it.value })
    }

    @Test
    fun `row order follows report then section then fact order`() {
        assertEquals(
            listOf("Refresh rate", "Renderer", "Hardware level", "Hardware level"),
            Snapshots.rowsOf(profile()).map { it.label },
        )
    }

    @Test
    fun `verdict provenance and domain all survive flattening`() {
        val row = Snapshots.rowsOf(profile()).first { it.label == "Renderer" }
        assertEquals(Support.SUPPORTED, row.support)
        assertEquals("queried", row.provenanceKind)
        assertEquals(Domain.GRAPHICS, row.domain)
        assertTrue(row.provenance.isNotBlank())
    }

    @Test
    fun `a new snapshot takes the scan time and the supplied identity`() {
        val identity = DeviceIdentity(
            manufacturer = "Google",
            model = "Pixel 8",
            device = "shiba",
            androidRelease = "14",
            apiLevel = 34,
            fingerprint = "google/shiba/shiba:14/AP1A/1:user/release-keys",
        )
        val snapshot = Snapshots.snapshotOf(profile(), identity, "My scan")

        assertEquals(Snapshot.UNSAVED_ID, snapshot.id)
        assertEquals("My scan", snapshot.name)
        assertEquals(1_700_000_000_000L, snapshot.capturedAtMillis)
        assertEquals("shiba", snapshot.device)
        assertEquals(34, snapshot.apiLevel)
        assertEquals(4, snapshot.rows.size)
    }

    /**
     * The identity comes from the caller, never from `Build`, so an old snapshot keeps
     * naming the device that produced it. This test is the guard on that seam.
     */
    @Test
    fun `the unknown identity is used verbatim rather than substituted`() {
        val snapshot = Snapshots.snapshotOf(profile(), DeviceIdentity.UNKNOWN, "name")
        assertEquals("Unknown", snapshot.manufacturer)
        assertEquals(0, snapshot.apiLevel)
        assertNotEquals("", snapshot.model)
    }

    @Test
    fun `every lab id is unique and non blank`() {
        val ids = Lab.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
        Lab.entries.forEach { assertEquals(it, Lab.fromId(it.id)) }
    }

    private fun profile() = CapabilityProfile(
        capturedAtMillis = 1_700_000_000_000L,
        reports = listOf(
            LabReport(
                lab = Lab.DISPLAY,
                sections = listOf(
                    Section("panel", "Panel", facts = listOf(fact("Refresh rate", "120 Hz")))
                ),
            ),
            LabReport(
                lab = Lab.GRAPHICS,
                sections = listOf(
                    Section(
                        "gpu",
                        "GPU",
                        facts = listOf(
                            fact("Renderer", "Adreno 740", Support.SUPPORTED, Domain.GRAPHICS)
                        ),
                    )
                ),
            ),
            LabReport(
                lab = Lab.CAMERA,
                sections = listOf(
                    Section(
                        id = "cameras",
                        title = "Cameras",
                        children = listOf(
                            Section(
                                "camera0",
                                "Camera 0",
                                facts = listOf(fact("Hardware level", "LEVEL_3")),
                            ),
                            Section(
                                "camera1",
                                "Camera 1",
                                facts = listOf(fact("Hardware level", "LIMITED")),
                            ),
                        ),
                    )
                ),
            ),
        ),
    )

    private fun fact(
        label: String,
        value: String,
        support: Support = Support.INFORMATIONAL,
        domain: Domain? = null,
    ) = Fact(label, value, Provenance.Queried("test"), support, domain)
}
