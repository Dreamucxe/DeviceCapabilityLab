package com.devicelab.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SnapshotDao {

    /**
     * The history list, newest first, with each snapshot's fact count.
     *
     * A LEFT JOIN rather than an inner one so a snapshot whose facts somehow failed to
     * insert still appears -- showing it with a count of zero is honest, silently
     * hiding it is not.
     */
    @Query(
        """
        SELECT s.id AS id, s.name AS name, s.captured_at AS captured_at,
               s.manufacturer AS manufacturer, s.model AS model,
               s.android_release AS android_release, s.api_level AS api_level,
               COUNT(f.id) AS fact_count
        FROM snapshots s
        LEFT JOIN snapshot_facts f ON f.snapshot_id = s.id
        GROUP BY s.id
        ORDER BY s.captured_at DESC, s.id DESC
        """
    )
    fun observeSummaries(): Flow<List<SnapshotSummaryRow>>

    @Query("SELECT COUNT(*) FROM snapshots")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM snapshots WHERE id = :id")
    suspend fun snapshot(id: Long): SnapshotEntity?

    @Query("SELECT * FROM snapshot_facts WHERE snapshot_id = :id ORDER BY position ASC")
    suspend fun facts(id: Long): List<FactEntity>

    @Insert
    suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Insert
    suspend fun insertFacts(facts: List<FactEntity>)

    @Query("UPDATE snapshots SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM snapshots WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM snapshots")
    suspend fun deleteAll()

    /**
     * Trims history to the newest [keep] snapshots.
     *
     * Bounded so a user who scans repeatedly does not silently fill their storage; the
     * limit is a setting, and deletion is always oldest-first so the newest scan is
     * never the one discarded.
     */
    @Query(
        """
        DELETE FROM snapshots WHERE id IN (
            SELECT id FROM snapshots ORDER BY captured_at DESC, id DESC
            LIMIT -1 OFFSET :keep
        )
        """
    )
    suspend fun trimTo(keep: Int)

    companion object {
        /**
         * How many fact rows go into one multi-row insert.
         *
         * SQLite's compiled-statement parameter limit is finite, and a full scan
         * produces well over a thousand rows of thirteen columns each -- a single
         * insert of all of them would exceed it on some platform versions. The
         * repository chunks by this before calling [insertFacts], inside one
         * transaction, so the save is still all-or-nothing.
         */
        const val FACT_INSERT_CHUNK = 300
    }
}
