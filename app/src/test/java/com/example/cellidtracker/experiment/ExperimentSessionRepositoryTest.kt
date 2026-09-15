package com.example.cellidtracker.experiment

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentSessionRepositoryTest {
    @Test
    fun formatSessionIdUsesStableTimestampSchema() {
        val timestamp = Instant.parse("2026-09-15T04:05:06.007Z").toEpochMilli()

        assertEquals(
            "20260915_040506_007",
            formatSessionId(timestamp, ZoneOffset.UTC)
        )
    }
}
