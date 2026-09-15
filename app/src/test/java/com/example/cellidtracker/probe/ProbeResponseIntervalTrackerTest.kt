package com.example.cellidtracker.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeResponseIntervalTrackerTest {
    @Test
    fun recordMeasuresConsecutiveResponsesAndResetStartsOver() {
        val tracker = ProbeResponseIntervalTracker()

        assertNull(tracker.record(1_000L))
        assertEquals(750L, tracker.record(1_750L))

        tracker.reset()

        assertNull(tracker.record(3_000L))
        assertEquals(250L, tracker.record(3_250L))
    }
}
