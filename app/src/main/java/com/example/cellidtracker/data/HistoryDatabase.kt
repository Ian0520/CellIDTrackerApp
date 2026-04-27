package com.example.cellidtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProbeHistoryEntity::class,
        ExperimentSessionEntity::class,
        ExperimentSampleEntity::class,
        ProbeRunEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): ProbeHistoryDao
    abstract fun experimentDao(): ExperimentDao
    abstract fun probeRunDao(): ProbeRunDao

    companion object {
        @Volatile
        private var INSTANCE: HistoryDatabase? = null

        fun getInstance(context: Context): HistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    "history.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9
                    )
                    .build().also { INSTANCE = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE probe_history ADD COLUMN towersCount INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE probe_history ADD COLUMN towersJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE probe_history ADD COLUMN moving INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE probe_history ADD COLUMN deltaMs INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `experiment_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `startedAtMillis` INTEGER NOT NULL,
                        `endedAtMillis` INTEGER,
                        `createdAtMillis` INTEGER NOT NULL,
                        `exportedAtMillis` INTEGER
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_experiment_sessions_sessionId` ON `experiment_sessions` (`sessionId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_experiment_sessions_endedAtMillis` ON `experiment_sessions` (`endedAtMillis`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_experiment_sessions_startedAtMillis` ON `experiment_sessions` (`startedAtMillis`)"
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `experiment_samples` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionDbId` INTEGER NOT NULL,
                        `recordedAtMillis` INTEGER NOT NULL,
                        `victim` TEXT NOT NULL,
                        `mcc` INTEGER NOT NULL,
                        `mnc` INTEGER NOT NULL,
                        `lac` INTEGER NOT NULL,
                        `cid` INTEGER NOT NULL,
                        `estimatedLat` REAL,
                        `estimatedLon` REAL,
                        `estimatedAccuracyM` REAL,
                        `geolocationStatus` TEXT NOT NULL,
                        `geolocationError` TEXT,
                        `towersCount` INTEGER NOT NULL,
                        `towersJson` TEXT NOT NULL,
                        `moving` INTEGER NOT NULL,
                        `deltaMs` INTEGER,
                        `createdAtMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionDbId`) REFERENCES `experiment_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_experiment_samples_sessionDbId` ON `experiment_samples` (`sessionDbId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_experiment_samples_recordedAtMillis` ON `experiment_samples` (`recordedAtMillis`)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Some paused/legacy branches created incompatible experiment schemas while
                // still marking DB version 6. Recreate experiment tables to guarantee
                // startup succeeds and probe history remains intact.
                database.execSQL("DROP TABLE IF EXISTS `experiment_samples`")
                database.execSQL("DROP TABLE IF EXISTS `experiment_sessions`")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `experiment_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `startedAtMillis` INTEGER NOT NULL,
                        `endedAtMillis` INTEGER,
                        `createdAtMillis` INTEGER NOT NULL,
                        `exportedAtMillis` INTEGER
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_experiment_sessions_sessionId` ON `experiment_sessions` (`sessionId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_experiment_sessions_endedAtMillis` ON `experiment_sessions` (`endedAtMillis`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_experiment_sessions_startedAtMillis` ON `experiment_sessions` (`startedAtMillis`)"
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `experiment_samples` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionDbId` INTEGER NOT NULL,
                        `recordedAtMillis` INTEGER NOT NULL,
                        `victim` TEXT NOT NULL,
                        `mcc` INTEGER NOT NULL,
                        `mnc` INTEGER NOT NULL,
                        `lac` INTEGER NOT NULL,
                        `cid` INTEGER NOT NULL,
                        `estimatedLat` REAL,
                        `estimatedLon` REAL,
                        `estimatedAccuracyM` REAL,
                        `geolocationStatus` TEXT NOT NULL,
                        `geolocationError` TEXT,
                        `towersCount` INTEGER NOT NULL,
                        `towersJson` TEXT NOT NULL,
                        `moving` INTEGER NOT NULL,
                        `deltaMs` INTEGER,
                        `createdAtMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionDbId`) REFERENCES `experiment_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_experiment_samples_sessionDbId` ON `experiment_samples` (`sessionDbId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_experiment_samples_recordedAtMillis` ON `experiment_samples` (`recordedAtMillis`)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `probe_runs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `victim` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `startedAtMillis` INTEGER NOT NULL,
                        `endedAtMillis` INTEGER,
                        `exitCode` INTEGER,
                        `stoppedByUser` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_probe_runs_victim` ON `probe_runs` (`victim`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_probe_runs_startedAtMillis` ON `probe_runs` (`startedAtMillis`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_probe_runs_endedAtMillis` ON `probe_runs` (`endedAtMillis`)"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE probe_history ADD COLUMN probeRunId INTEGER")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_probe_history_probeRunId` ON `probe_history` (`probeRunId`)"
                )
            }
        }
    }
}
