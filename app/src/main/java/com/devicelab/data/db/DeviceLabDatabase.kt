package com.devicelab.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The snapshot database.
 *
 * Version 1, and there is deliberately no `fallbackToDestructiveMigration`. The
 * snapshots a user saved are the only data in this app that cannot be regenerated --
 * a scan of a device they no longer own, or of an Android version they have since
 * upgraded past, is gone for good if it is dropped. So a schema change here has to
 * come with a real migration, and the schema is exported (see `room.schemaLocation` in
 * the build file) so that migration can be tested against the actual historical
 * schema rather than a reconstruction of it.
 */
@Database(
    entities = [SnapshotEntity::class, FactEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DeviceLabDatabase : RoomDatabase() {

    abstract fun snapshotDao(): SnapshotDao

    companion object {
        const val NAME = "device-lab.db"

        fun build(context: Context): DeviceLabDatabase =
            Room.databaseBuilder(context, DeviceLabDatabase::class.java, NAME).build()
    }
}
