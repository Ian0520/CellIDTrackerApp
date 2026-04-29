package com.example.cellidtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProbeHistoryDao {
    @Query("SELECT DISTINCT victim FROM probe_history ORDER BY victim ASC")
    suspend fun getVictims(): List<String>

    @Query("SELECT * FROM probe_history WHERE victim = :victim ORDER BY timestampMillis DESC")
    suspend fun getHistoryForVictim(victim: String): List<ProbeHistoryEntity>

    @Query("SELECT * FROM probe_history WHERE victim = :victim ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun getRecentHistoryForVictim(victim: String, limit: Int): List<ProbeHistoryEntity>

    @Query("SELECT * FROM probe_history ORDER BY timestampMillis DESC")
    suspend fun getAll(): List<ProbeHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ProbeHistoryEntity): Long

    @Query("DELETE FROM probe_history WHERE victim = :victim")
    suspend fun clearForVictim(victim: String)
}
