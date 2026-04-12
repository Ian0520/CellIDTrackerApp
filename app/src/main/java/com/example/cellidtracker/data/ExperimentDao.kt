package com.example.cellidtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExperimentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(entry: ExperimentSessionEntity): Long

    @Query(
        "SELECT * FROM experiment_sessions " +
            "WHERE endedAtMillis IS NULL " +
            "ORDER BY startedAtMillis DESC LIMIT 1"
    )
    suspend fun getActiveSession(): ExperimentSessionEntity?

    @Query("SELECT * FROM experiment_sessions WHERE id = :sessionDbId LIMIT 1")
    suspend fun getSessionById(sessionDbId: Long): ExperimentSessionEntity?

    @Query("UPDATE experiment_sessions SET endedAtMillis = :endedAtMillis WHERE id = :sessionDbId")
    suspend fun endSession(sessionDbId: Long, endedAtMillis: Long)

    @Query("UPDATE experiment_sessions SET exportedAtMillis = :exportedAtMillis WHERE id = :sessionDbId")
    suspend fun updateSessionExportTimestamp(sessionDbId: Long, exportedAtMillis: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSample(entry: ExperimentSampleEntity): Long

    @Query(
        "SELECT * FROM experiment_samples " +
            "WHERE sessionDbId = :sessionDbId " +
            "ORDER BY recordedAtMillis ASC, id ASC"
    )
    suspend fun getSamplesForSession(sessionDbId: Long): List<ExperimentSampleEntity>
}
