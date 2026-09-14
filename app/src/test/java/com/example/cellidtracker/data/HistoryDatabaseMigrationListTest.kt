package com.example.cellidtracker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDatabaseMigrationListTest {
    @Test
    fun registeredMigrationsCoverEveryVersionThroughCurrentSchema() {
        val versionPairs = HistoryDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }

        assertEquals((1 until 12).map { it to it + 1 }, versionPairs)
    }
}
