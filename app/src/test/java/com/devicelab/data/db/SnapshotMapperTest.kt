package com.devicelab.data.db

import com.devicelab.core.model.CapabilityProfile
import com.devicelab.core.model.DeviceIdentity
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Fact
import com.devicelab.core.model.FactRow
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Provenance
import com.devicelab.core.model.Section
import com.devicelab.core.model.Snapshot
import com.devicelab.core.model.Snapshots
import com.devicelab.core.model.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The database boundary.
 *
 * A snapshot exists to be compared with a later scan, possibly months later and possibly
 * after an OS upgrade. That makes fidelity the whole point: a value that changes on the
 * way through storage would show up as a device change that never happened.
 *
 * The other property tested here is the enum fallback. Enums cross this boundary by name,
 * and a name written by a later version of the app must not throw on an earlier one --
 * that would take out the whole History tab because of one row.
 */
class SnapshotMapperTest {

    @Test
    fun `a snapshot header survives the round trip unchanged`() {
        val snapshot = snapshot()
        val restored = SnapshotMapper.toModel(SnapshotMapper.toEntity(snapshot), emptyList())
        assertEquals(snapshot.copy(rows = emptyList()), restored)
    }

    @Test
    fun `every fact row survives the round trip unchanged`() {
        val snapshot = snapshot()
        val entities = SnapshotMapper.toEntities(snapshotId = 7, rows = snapshot.rows)
        val restored = entities.map(SnapshotMapper::toRow)
        assertEquals(snapshot.rows, restored)
    }

    /**
     * Order is stored explicitly because SQLite makes no promise about the order rows come
     * back in. Without it a restored snapshot would read in an arbitrary order, and the
     * diff -- which pairs by key, not position -- would still be right while the screen
     * looked scrambled.
     */
    @Test
    fun `the position column records the order the detectors produced`() {
        val entities = SnapshotMapper.toEntities(1, snapshot().rows)
        assertEquals(entities.indices.toList(), entities.map { it.position })
    }

    @Test
    fun `every fact is attributed to the snapshot it belongs to`() {
        val entities = SnapshotMapper.toEntities(snapshotId = 42, rows = snapshot().rows)
        assertTrue(entities.isNotEmpty())
        assertTrue(entities.all { it.snapshotId == 42L })
    }

    /** Tokens, not ordinals: inserting an enum value must not reinterpret stored rows. */
    @Test
    fun `enums are stored as their names`() {
        val entity = SnapshotMapper.toEntities(1, listOf(row(Support.PARTIAL, Domain.CAMERA))).single()
        assertEquals("PARTIAL", entity.support)
        assertEquals("CAMERA", entity.domain)
    }

    @Test
    fun `a fact with no domain stores null rather than a placeholder`() {
        val entity = SnapshotMapper.toEntities(1, listOf(row(Support.SUPPORTED, null))).single()
        assertNull(entity.domain)
        assertNull(SnapshotMapper.toRow(entity).domain)
    }

    @Test
    fun `every support value round trips by name`() {
        Support.entries.forEach { support ->
            val entity = SnapshotMapper.toEntities(1, listOf(row(support, null))).single()
            assertEquals(support, SnapshotMapper.toRow(entity).support)
        }
    }

    @Test
    fun `every domain round trips by name`() {
        Domain.entries.forEach { domain ->
            val entity = SnapshotMapper.toEntities(1, listOf(row(Support.SUPPORTED, domain))).single()
            assertEquals(domain, SnapshotMapper.toRow(entity).domain)
        }
    }

    /**
     * The forward-compatibility guard. A database written by a later version, restored
     * onto this one, must degrade one row rather than crash the History tab.
     */
    @Test
    fun `an unrecognised support name becomes unknown rather than throwing`() {
        val entity = SnapshotMapper.toEntities(1, listOf(row(Support.SUPPORTED, null)))
            .single()
            .copy(support = "SOMETHING_FROM_A_LATER_VERSION")
        assertEquals(Support.UNKNOWN, SnapshotMapper.toRow(entity).support)
    }

    @Test
    fun `an unrecognised domain name becomes no domain rather than throwing`() {
        val entity = SnapshotMapper.toEntities(1, listOf(row(Support.SUPPORTED, Domain.CAMERA)))
            .single()
            .copy(domain = "THERMAL")
        assertNull(SnapshotMapper.toRow(entity).domain)
    }

    @Test
    fun `an empty support token is unknown rather than an exception`() {
        val entity = SnapshotMapper.toEntities(1, listOf(row(Support.SUPPORTED, null)))
            .single()
            .copy(support = "")
        assertEquals(Support.UNKNOWN, SnapshotMapper.toRow(entity).support)
    }

    /** Case matters, because the token is the enum name and nothing else. */
    @Test
    fun `a lowercased support token is not silently accepted`() {
        val entity = SnapshotMapper.toEntities(1, listOf(row(Support.SUPPORTED, null)))
            .single()
            .copy(support = "supported")
        assertEquals(Support.UNKNOWN, SnapshotMapper.toRow(entity).support)
    }

    @Test
    fun `a summary row maps to the history model`() {
        val summary = SnapshotMapper.toSummary(
            SnapshotSummaryRow(
                id = 3,
                name = "Before the update",
                capturedAt = 1_700_000_000_000L,
                manufacturer = "Google",
                model = "Pixel 8",
                androidRelease = "14",
                apiLevel = 34,
                factCount = 1_284,
            )
        )
        assertEquals(3L, summary.id)
        assertEquals("Before the update", summary.name)
        assertEquals(1_700_000_000_000L, summary.capturedAtMillis)
        assertEquals("Pixel 8", summary.model)
        assertEquals(34, summary.apiLevel)
        assertEquals(1_284, summary.factCount)
    }

    /**
     * The end-to-end path: a live scan, flattened, stored, restored. Every value the
     * screens and the diff read has to be identical on the far side.
     */
    @Test
    fun `a scan survives being flattened stored and restored`() {
        val profile = CapabilityProfile(
            capturedAtMillis = 1_700_000_000_000L,
            reports = listOf(
                LabReport(
                    lab = Lab.DISPLAY,
                    sections = listOf(
                        Section(
                            id = "panel",
                            title = "Panel",
                            facts = listOf(
                                Fact(
                                    label = "HDR10+",
                                    value = "Supported",
                                    provenance = Provenance.Queried("Display.getHdrCapabilities()"),
                                    support = Support.SUPPORTED,
                                    domain = Domain.DISPLAY,
                                    detail = "HDR_TYPE_HDR10_PLUS is present",
                                ),
                                Fact(
                                    label = "Refresh rate",
                                    value = "120 Hz",
                                    provenance = Provenance.Queried("Display.getMode()"),
                                ),
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
                                    id = "camera0",
                                    title = "Camera 0",
                                    facts = listOf(
                                        Fact(
                                            label = "Stream use cases",
                                            value = "Not exposed by Android",
                                            provenance = Provenance.RequiresApi("SCALER", 36, 34),
                                            support = Support.NOT_EXPOSED,
                                            domain = Domain.CAMERA,
                                        ),
                                    ),
                                )
                            ),
                        )
                    ),
                ),
            ),
        )

        val saved = Snapshots.snapshotOf(
            profile,
            DeviceIdentity("Google", "Pixel 8", "shiba", "14", 34, "google/shiba/..."),
            "Baseline",
        )
        val restored = SnapshotMapper.toModel(
            SnapshotMapper.toEntity(saved.copy(id = 5)),
            SnapshotMapper.toEntities(5, saved.rows),
        )

        assertEquals(5L, restored.id)
        assertEquals(saved.rows, restored.rows)
        assertEquals(saved.capturedAtMillis, restored.capturedAtMillis)
        assertEquals(3, restored.rows.size)

        // The API-gated row keeps the reason it was gated, which is what makes a later
        // comparison able to say "this became available" rather than "this appeared".
        val gated = restored.rows.first { it.label == "Stream use cases" }
        assertEquals("requires-api", gated.provenanceKind)
        assertTrue(gated.provenance.contains("Requires API 36+"))
        assertEquals(Domain.CAMERA, gated.domain)
        assertEquals("cameras/camera0", gated.sectionPath)
    }

    private fun snapshot() = Snapshot(
        id = 1,
        name = "Baseline",
        capturedAtMillis = 1_700_000_000_000L,
        manufacturer = "Google",
        model = "Pixel 8",
        device = "shiba",
        androidRelease = "14",
        apiLevel = 34,
        fingerprint = "google/shiba/shiba:14/AP1A/1:user/release-keys",
        rows = listOf(
            row(Support.SUPPORTED, Domain.DISPLAY),
            row(Support.NOT_EXPOSED, null).copy(key = "display/panel/Bit depth", label = "Bit depth"),
            row(Support.INFORMATIONAL, null).copy(key = "display/panel/Refresh", label = "Refresh"),
        ),
    )

    private fun row(support: Support, domain: Domain?) = FactRow(
        key = "display/panel/HDR10+",
        labId = "display",
        sectionPath = "panel",
        sectionTitle = "Panel",
        label = "HDR10+",
        value = "Supported",
        support = support,
        provenanceKind = "queried",
        provenance = "Queried · Display.getHdrCapabilities()",
        detail = "HDR_TYPE_HDR10_PLUS is present",
        domain = domain,
    )
}
