package com.devicelab.data.db

import com.devicelab.core.model.Domain
import com.devicelab.core.model.FactRow
import com.devicelab.core.model.Snapshot
import com.devicelab.core.model.SnapshotSummary
import com.devicelab.core.model.Support

/**
 * Between database rows and the domain model.
 *
 * Kept out of both the entities and the model so neither knows about the other: the
 * model can be unit-tested with no Room on the classpath, and the schema can gain a
 * column without changing anything the UI reads.
 *
 * Enums cross this boundary by name, and an unrecognised name maps to a defined
 * fallback rather than throwing. A snapshot written by a later version of the app
 * whose database is restored onto an earlier one would otherwise crash the History
 * tab; showing that one row as [Support.UNKNOWN] loses a little information and keeps
 * everything else readable.
 */
object SnapshotMapper {

    fun toEntity(snapshot: Snapshot): SnapshotEntity = SnapshotEntity(
        id = snapshot.id,
        name = snapshot.name,
        capturedAt = snapshot.capturedAtMillis,
        manufacturer = snapshot.manufacturer,
        model = snapshot.model,
        device = snapshot.device,
        androidRelease = snapshot.androidRelease,
        apiLevel = snapshot.apiLevel,
        fingerprint = snapshot.fingerprint,
    )

    fun toEntities(snapshotId: Long, rows: List<FactRow>): List<FactEntity> =
        rows.mapIndexed { index, row ->
            FactEntity(
                snapshotId = snapshotId,
                position = index,
                key = row.key,
                labId = row.labId,
                sectionPath = row.sectionPath,
                sectionTitle = row.sectionTitle,
                label = row.label,
                value = row.value,
                support = row.support.name,
                provenanceKind = row.provenanceKind,
                provenance = row.provenance,
                detail = row.detail,
                domain = row.domain?.name,
            )
        }

    fun toModel(snapshot: SnapshotEntity, facts: List<FactEntity>): Snapshot = Snapshot(
        id = snapshot.id,
        name = snapshot.name,
        capturedAtMillis = snapshot.capturedAt,
        manufacturer = snapshot.manufacturer,
        model = snapshot.model,
        device = snapshot.device,
        androidRelease = snapshot.androidRelease,
        apiLevel = snapshot.apiLevel,
        fingerprint = snapshot.fingerprint,
        rows = facts.map(::toRow),
    )

    fun toRow(entity: FactEntity): FactRow = FactRow(
        key = entity.key,
        labId = entity.labId,
        sectionPath = entity.sectionPath,
        sectionTitle = entity.sectionTitle,
        label = entity.label,
        value = entity.value,
        support = support(entity.support),
        provenanceKind = entity.provenanceKind,
        provenance = entity.provenance,
        detail = entity.detail,
        domain = domain(entity.domain),
    )

    fun toSummary(row: SnapshotSummaryRow): SnapshotSummary = SnapshotSummary(
        id = row.id,
        name = row.name,
        capturedAtMillis = row.capturedAt,
        manufacturer = row.manufacturer,
        model = row.model,
        androidRelease = row.androidRelease,
        apiLevel = row.apiLevel,
        factCount = row.factCount,
    )

    private fun support(name: String): Support =
        Support.entries.firstOrNull { it.name == name } ?: Support.UNKNOWN

    private fun domain(name: String?): Domain? =
        name?.let { n -> Domain.entries.firstOrNull { it.name == n } }
}
