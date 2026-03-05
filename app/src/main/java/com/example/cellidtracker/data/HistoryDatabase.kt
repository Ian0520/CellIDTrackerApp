package com.example.cellidtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ProbeHistoryEntity::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): ProbeHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: HistoryDatabase? = null

        fun getInstance(context: Context): HistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    "history.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
