package com.example.cellidtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProbeRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: ProbeRunEntity): Long

    @Query(
        "UPDATE probe_runs " +
            "SET endedAtMillis = :endedAtMillis, exitCode = :exitCode, stoppedByUser = :stoppedByUser " +
            "WHERE id = :id"
    )
    suspend fun endRun(
        id: Long,
        endedAtMillis: Long,
        exitCode: Int?,
        stoppedByUser: Boolean
    )

    @Query("SELECT * FROM probe_runs WHERE victim = :victim ORDER BY startedAtMillis ASC, id ASC")
    suspend fun getRunsForVictim(victim: String): List<ProbeRunEntity>

    @Query(
        "SELECT * FROM (" +
            "SELECT * FROM probe_runs WHERE victim = :victim ORDER BY startedAtMillis DESC, id DESC LIMIT :limit" +
            ") ORDER BY startedAtMillis ASC, id ASC"
    )
    suspend fun getRecentRunsForVictim(victim: String, limit: Int): List<ProbeRunEntity>

    @Query("SELECT DISTINCT victim FROM probe_runs ORDER BY victim ASC")
    suspend fun getVictims(): List<String>

    @Query("DELETE FROM probe_runs WHERE victim = :victim")
    suspend fun clearForVictim(victim: String)
}
