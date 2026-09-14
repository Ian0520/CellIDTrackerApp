package com.example.cellidtracker.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeAttemptReducerTest {
    @Test
    fun normalSequenceProducesOneEvolvingAttempt() {
        val reducer = ProbeAttemptReducer()

        val started = requireNotNull(reducer.apply(startEvent()))
        val provisional = requireNotNull(reducer.apply(provisionalEvent()))
        val cell = requireNotNull(reducer.apply(cellEvent()))
        val finished = requireNotNull(reducer.apply(finishedEvent()))

        assertTrue(started.created)
        assertTrue(provisional.provisionalAdded)
        assertTrue(cell.cellAdded)
        assertTrue(finished.finishAdded)
        assertFalse(finished.created)
        assertEquals("cell_observed", finished.snapshot.outcome)
        assertEquals(749L, finished.snapshot.deltaMs)
        assertEquals(ParsedCellFromLog(466, 92, 13_700, 81_261_593), finished.snapshot.parsedCell)
    }

    @Test
    fun repeatedNativeLinesDoNotCreateChanges() {
        val reducer = ProbeAttemptReducer()

        reducer.apply(startEvent())
        reducer.apply(provisionalEvent())
        reducer.apply(cellEvent())
        reducer.apply(finishedEvent())

        assertNull(reducer.apply(startEvent()))
        assertNull(reducer.apply(provisionalEvent().copy(deltaMs = 500)))
        assertNull(reducer.apply(cellEvent().copy(parsedCell = ParsedCellFromLog(1, 2, 3, 4))))
        assertNull(reducer.apply(finishedEvent().copy(outcome = "different")))
    }

    @Test
    fun outOfOrderCellThenStartStillProducesOneCompleteAttempt() {
        val reducer = ProbeAttemptReducer()

        val cell = requireNotNull(reducer.apply(cellEvent()))
        val lateStart = reducer.apply(startEvent())
        val finished = requireNotNull(reducer.apply(finishedEvent()))

        assertTrue(cell.created)
        assertTrue(cell.provisionalAdded)
        assertTrue(cell.cellAdded)
        assertNull(lateStart)
        assertEquals(100L, finished.snapshot.inviteElapsedMs)
        assertEquals(849L, finished.snapshot.responseElapsedMs)
        assertEquals(1_700_000_001_000L, finished.snapshot.finishedUnixMs)
    }

    @Test
    fun boundedReducerEvictsOldestAttempt() {
        val reducer = ProbeAttemptReducer(maxTrackedAttempts = 2)
        reducer.apply(startEvent("call-1"))
        reducer.apply(startEvent("call-2"))
        reducer.apply(startEvent("call-3"))

        assertNull(reducer.snapshot("call-1"))
        assertEquals("call-2", reducer.snapshot("call-2")?.attemptId)
        assertEquals("call-3", reducer.snapshot("call-3")?.attemptId)
    }

    private fun startEvent(attemptId: String = "call-1") = ProbeAttemptStartedEvent(
        contractVersion = 1,
        attemptId = attemptId,
        inviteElapsedMs = 100,
        inviteUnixMs = 1_700_000_000_000
    )

    private fun provisionalEvent() = ProbeProvisionalReceivedEvent(
        contractVersion = 1,
        attemptId = "call-1",
        status = 183,
        deltaMs = 749,
        inviteElapsedMs = 100,
        responseElapsedMs = 849,
        inviteUnixMs = 1_700_000_000_000,
        responseUnixMs = 1_700_000_000_749
    )

    private fun cellEvent() = ProbeCellObservedEvent(
        contractVersion = 1,
        attemptId = "call-1",
        status = 183,
        deltaMs = 749,
        inviteElapsedMs = 100,
        responseElapsedMs = 849,
        inviteUnixMs = 1_700_000_000_000,
        responseUnixMs = 1_700_000_000_749,
        parsedCell = ParsedCellFromLog(466, 92, 13_700, 81_261_593)
    )

    private fun finishedEvent() = ProbeAttemptFinishedEvent(
        contractVersion = 1,
        attemptId = "call-1",
        finishedUnixMs = 1_700_000_001_000,
        outcome = "cell_observed",
        reason = "next_invite"
    )
}
