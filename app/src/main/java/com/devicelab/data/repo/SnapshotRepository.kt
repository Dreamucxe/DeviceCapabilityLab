package com.devicelab.data.repo

import androidx.room.withTransaction
import com.devicelab.core.common.Timestamps
import com.devicelab.core.model.CapabilityProfile
import com.devicelab.core.model.DeviceIdentity
import com.devicelab.core.model.Snapshot
import com.devicelab.core.model.SnapshotComparer
import com.devicelab.core.model.SnapshotDiff
import com.devicelab.core.model.SnapshotSummary
import com.devicelab.core.model.Snapshots
import com.devicelab.data.db.DeviceLabDatabase
import com.devicelab.data.db.SnapshotDao
import com.devicelab.data.db.SnapshotMapper
import com.devicelab.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved scans: store, list, load, rename, delete, compare.
 *
 * All of it is offline and local. Section 26 rules out any server or account, so there
 * is no sync, no upload and no remote copy -- a snapshot exists on the device that took
 * it, and leaves only through the user's own export.
 */
@Singleton
class SnapshotRepository @Inject constructor(
    private val database: DeviceLabDatabase,
    private val dao: SnapshotDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observeSummaries(): Flow<List<SnapshotSummary>> =
        dao.observeSummaries().map { rows -> rows.map(SnapshotMapper::toSummary) }

    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * Saves a scan and returns its new id.
     *
     * One transaction covers the header and every fact row, so an interrupted save
     * leaves nothing behind. A half-written snapshot would be worse than a failed one:
     * it would appear in history and then compare as though hundreds of capabilities
     * had vanished from the device.
     */
    suspend fun save(snapshot: Snapshot, keepAtMost: Int): Long = withContext(io) {
        database.withTransaction {
            val id = dao.insertSnapshot(SnapshotMapper.toEntity(snapshot.copy(id = 0)))
            SnapshotMapper.toEntities(id, snapshot.rows)
                .chunked(SnapshotDao.FACT_INSERT_CHUNK)
                .forEach { dao.insertFacts(it) }
            if (keepAtMost > 0) dao.trimTo(keepAtMost)
            id
        }
    }

    /** Saves the current scan, naming it after the device and time when [name] is blank. */
    suspend fun saveScan(
        profile: CapabilityProfile,
        identity: DeviceIdentity,
        keepAtMost: Int,
        name: String? = null,
    ): Long = save(
        Snapshots.snapshotOf(
            profile = profile,
            identity = identity,
            name = name?.takeIf { it.isNotBlank() } ?: defaultName(identity, profile),
        ),
        keepAtMost,
    )

    suspend fun load(id: Long): Snapshot? = withContext(io) {
        val header = dao.snapshot(id) ?: return@withContext null
        SnapshotMapper.toModel(header, dao.facts(id))
    }

    suspend fun rename(id: Long, name: String) = withContext(io) {
        dao.rename(id, name.trim().ifBlank { "Untitled snapshot" })
    }

    suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    suspend fun deleteAll() = withContext(io) { dao.deleteAll() }

    /**
     * Compares two saved snapshots.
     *
     * Returns null when either is missing rather than throwing, because the only way to
     * reach this with a stale id is a snapshot deleted from another screen while the
     * comparison was open -- a race, not an error worth a crash.
     */
    suspend fun compare(leftId: Long, rightId: Long): SnapshotDiff? = withContext(io) {
        val left = load(leftId) ?: return@withContext null
        val right = load(rightId) ?: return@withContext null
        SnapshotComparer.compare(left, right)
    }

    /**
     * Compares a saved snapshot against the live scan.
     *
     * The live side is built through the same [Snapshots.snapshotOf] the saved side went
     * through, so the two are compared on identical footing. Comparing a stored row
     * against a freshly-walked section tree would risk the diff reporting a difference
     * that only existed in how the two were assembled.
     */
    suspend fun compareWithLive(
        savedId: Long,
        profile: CapabilityProfile,
        identity: DeviceIdentity,
    ): SnapshotDiff? = withContext(io) {
        val saved = load(savedId) ?: return@withContext null
        val live = Snapshots.snapshotOf(profile, identity, "Current scan")
        SnapshotComparer.compare(saved, live)
    }

    private fun defaultName(
        identity: DeviceIdentity,
        profile: CapabilityProfile,
    ): String = "${identity.model} · ${Timestamps.readable(profile.capturedAtMillis)}"
}
