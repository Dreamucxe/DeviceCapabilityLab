package com.devicelab.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A saved scan's header row.
 *
 * Device identity is denormalised onto the snapshot on purpose. A snapshot has to keep
 * saying which device and which Android version produced it forever, including after
 * the phone is updated or the database is restored on different hardware, so these
 * cannot be read from `Build` at display time.
 */
@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    val manufacturer: String,
    val model: String,
    val device: String,
    @ColumnInfo(name = "android_release") val androidRelease: String,
    @ColumnInfo(name = "api_level") val apiLevel: Int,
    val fingerprint: String,
)

/**
 * One fact inside a saved scan.
 *
 * [snapshotId] cascades on delete so removing a snapshot cannot leave orphaned facts
 * behind -- a scan produces well over a thousand of these, and a leak would grow the
 * database without bound. The index on it is what keeps loading one snapshot's rows a
 * lookup rather than a table scan.
 *
 * [support] and [provenanceKind] are stored as their stable string tokens, not as
 * enum ordinals. An ordinal would silently change meaning the moment a value is
 * inserted into the enum, and it would reinterpret every already-saved row rather
 * than failing visibly.
 *
 * [position] preserves the order the detectors produced, so a restored snapshot reads
 * in report order rather than in whatever order SQLite returns.
 */
@Entity(
    tableName = "snapshot_facts",
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("snapshot_id")],
)
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "snapshot_id") val snapshotId: Long,
    val position: Int,
    @ColumnInfo(name = "fact_key") val key: String,
    @ColumnInfo(name = "lab_id") val labId: String,
    @ColumnInfo(name = "section_path") val sectionPath: String,
    @ColumnInfo(name = "section_title") val sectionTitle: String,
    val label: String,
    val value: String,
    val support: String,
    @ColumnInfo(name = "provenance_kind") val provenanceKind: String,
    val provenance: String,
    val detail: String?,
    val domain: String?,
)

/** The history list's row, assembled by SQL so no fact rows are loaded for it. */
data class SnapshotSummaryRow(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    val manufacturer: String,
    val model: String,
    @ColumnInfo(name = "android_release") val androidRelease: String,
    @ColumnInfo(name = "api_level") val apiLevel: Int,
    @ColumnInfo(name = "fact_count") val factCount: Int,
)
