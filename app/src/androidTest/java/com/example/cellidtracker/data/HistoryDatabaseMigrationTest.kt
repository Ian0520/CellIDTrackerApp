package com.example.cellidtracker.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HistoryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private lateinit var context: Context
    private var database: HistoryDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
        context.deleteDatabase(EXPORTED_SCHEMA_DATABASE)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE)
        context.deleteDatabase(EXPORTED_SCHEMA_DATABASE)
    }

    @Test
    fun migration10To12PreservesExistingExperimentData() {
        createVersion10Database()

        database = Room.databaseBuilder(context, HistoryDatabase::class.java, TEST_DATABASE)
            .addMigrations(*HistoryDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val migrated = requireNotNull(database).openHelper.writableDatabase
        assertEquals(12, migrated.version)

        migrated.query(
            "SELECT sessionId, startedAtMillis, experimentId, clockOffsetMs " +
                "FROM experiment_sessions WHERE id = 11"
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("20260914_120000_000", cursor.getString(0))
            assertEquals(1_000L, cursor.getLong(1))
            assertNull(cursor.getString(2))
            assertEquals(true, cursor.isNull(3))
        }

        migrated.query(
            "SELECT victim, deltaMs, probeId, inviteSentAtMillis, wifiRssiDbm " +
                "FROM experiment_samples WHERE id = 21"
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("sanitized-target", cursor.getString(0))
            assertEquals(749L, cursor.getLong(1))
            assertEquals(true, cursor.isNull(2))
            assertEquals(true, cursor.isNull(3))
            assertEquals(true, cursor.isNull(4))
        }

        migrated.query("SELECT COUNT(*) FROM probe_attempts").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration11To12CreatesAttemptTableWithoutChangingLegacyRows() {
        migrationHelper.createDatabase(TEST_DATABASE, 11).apply {
            execSQL(
                """
                INSERT INTO experiment_sessions (
                    id, sessionId, startedAtMillis, endedAtMillis, createdAtMillis,
                    exportedAtMillis, experimentId, clockOffsetMs,
                    clockUncertaintyMs, clockMeasuredAtMillis
                ) VALUES (
                    31, '20260914_130000_000', 3000, 4000, 3000,
                    NULL, NULL, NULL, NULL, NULL
                )
                """.trimIndent()
            )
            close()
        }

        database = Room.databaseBuilder(context, HistoryDatabase::class.java, TEST_DATABASE)
            .addMigrations(*HistoryDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val migrated = requireNotNull(database).openHelper.writableDatabase
        assertEquals(12, migrated.version)
        migrated.query("SELECT sessionId FROM experiment_sessions WHERE id = 31").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("20260914_130000_000", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM probe_attempts").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun exportedVersion12SchemaCanBeCreated() {
        migrationHelper.createDatabase(EXPORTED_SCHEMA_DATABASE, 12).close()
    }

    private fun createVersion10Database() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DATABASE)
            .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createVersion10Schema(db)
                    insertVersion10Fixture(db)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = error("Unexpected test-helper upgrade from $oldVersion to $newVersion")
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(configuration).also { helper ->
            helper.writableDatabase
            helper.close()
        }
    }

    private fun createVersion10Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `probe_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `victim` TEXT NOT NULL,
                `mcc` INTEGER NOT NULL,
                `mnc` INTEGER NOT NULL,
                `lac` INTEGER NOT NULL,
                `cid` INTEGER NOT NULL,
                `lat` REAL,
                `lon` REAL,
                `accuracy` REAL,
                `timestampMillis` INTEGER NOT NULL,
                `towersCount` INTEGER NOT NULL,
                `towersJson` TEXT NOT NULL,
                `moving` INTEGER NOT NULL,
                `deltaMs` INTEGER,
                `probeRunId` INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
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
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_experiment_sessions_sessionId` " +
                "ON `experiment_sessions` (`sessionId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_experiment_sessions_endedAtMillis` " +
                "ON `experiment_sessions` (`endedAtMillis`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_experiment_sessions_startedAtMillis` " +
                "ON `experiment_sessions` (`startedAtMillis`)"
        )
        db.execSQL(
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
                `sampleType` TEXT NOT NULL DEFAULT 'cell',
                `sipStatus` INTEGER,
                `inviteMs` INTEGER,
                `prMs` INTEGER,
                `intercarrierCandidate` INTEGER,
                `createdAtMillis` INTEGER NOT NULL,
                FOREIGN KEY(`sessionDbId`) REFERENCES `experiment_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_experiment_samples_sessionDbId` " +
                "ON `experiment_samples` (`sessionDbId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_experiment_samples_recordedAtMillis` " +
                "ON `experiment_samples` (`recordedAtMillis`)"
        )
        db.execSQL(
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
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_probe_runs_victim` ON `probe_runs` (`victim`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_probe_runs_startedAtMillis` " +
                "ON `probe_runs` (`startedAtMillis`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_probe_runs_endedAtMillis` " +
                "ON `probe_runs` (`endedAtMillis`)"
        )
    }

    private fun insertVersion10Fixture(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO experiment_sessions (
                id, sessionId, startedAtMillis, endedAtMillis, createdAtMillis, exportedAtMillis
            ) VALUES (11, '20260914_120000_000', 1000, 2500, 1000, NULL)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO experiment_samples (
                id, sessionDbId, recordedAtMillis, victim, mcc, mnc, lac, cid,
                estimatedLat, estimatedLon, estimatedAccuracyM, geolocationStatus,
                geolocationError, towersCount, towersJson, moving, deltaMs,
                sampleType, sipStatus, inviteMs, prMs, intercarrierCandidate, createdAtMillis
            ) VALUES (
                21, 11, 2000, 'sanitized-target', 466, 92, 13700, 81261593,
                25.0, 121.0, 900.0, 'success', NULL, 1, '[]', 0, 749,
                'cell', 183, 799870333, 799871082, 0, 2000
            )
            """.trimIndent()
        )
    }

    private companion object {
        const val TEST_DATABASE = "history-migration-test.db"
        const val EXPORTED_SCHEMA_DATABASE = "history-exported-schema-test.db"
    }
}
