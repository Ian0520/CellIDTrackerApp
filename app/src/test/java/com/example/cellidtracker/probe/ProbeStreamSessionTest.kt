package com.example.cellidtracker.probe

import com.example.cellidtracker.ProbeNetworkSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeStreamSessionTest {
    @Test
    fun streamReadyMakesStructuredContractAuthoritative() = runBlocking {
        val session = newSession()

        val ready = session.accept(streamReadyLine())
        val legacyDelta = session.accept(legacyDeltaLine())
        val legacyCell = session.accept(legacyCellLine())
        session.close()

        val changes = mutableListOf<ProbeAttemptChange>()
        session.consume(
            onAttemptChange = { _, change, _ -> changes += change },
            onFailure = { throw it }
        )

        assertTrue(ready.structuredEvent is ProbeStreamReadyEvent)
        assertFalse(ready.structuredQueueFailed)
        assertNull(legacyDelta.legacyDeltaEvent)
        assertNull(legacyCell.legacyCellEvent)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun duplicateStructuredEventsProduceOneChangePerStage() = runBlocking {
        var now = 10_000L
        var contextCaptures = 0
        val session = newSession(
            nowMillis = { now++ },
            onContextCaptured = { contextCaptures += 1 }
        )
        val started = startedLine()
        val provisional = provisionalLine()
        val cell = cellLine()
        val finished = finishedLine()

        session.accept(streamReadyLine())
        session.accept(started)
        session.accept(started)
        session.accept(provisional)
        session.accept(provisional)
        session.accept(cell)
        session.accept(cell)
        session.accept(finished)
        session.accept(finished)
        session.close()

        val events = mutableListOf<ProbeAttemptEvent>()
        val changes = mutableListOf<ProbeAttemptChange>()
        session.consume(
            onAttemptChange = { event, change, context ->
                events += event
                changes += change
                assertEquals("victim", context.victim)
            },
            onFailure = { throw it }
        )

        assertEquals(4, changes.size)
        assertTrue(events[0] is ProbeAttemptStartedEvent)
        assertTrue(events[1] is ProbeProvisionalReceivedEvent)
        assertTrue(events[2] is ProbeCellObservedEvent)
        assertTrue(events[3] is ProbeAttemptFinishedEvent)
        assertEquals(1, changes.count { it.provisionalAdded })
        assertEquals(1, changes.count { it.cellAdded })
        assertEquals(1, changes.count { it.finishAdded })
        assertEquals(1, contextCaptures)
        assertEquals(10_001L, session.lastProbeEventAtMillis.get())
    }

    @Test
    fun legacyEventsAreDeduplicatedBeforeDispatch() {
        val session = newSession()

        val firstDelta = session.accept(legacyDeltaLine())
        val repeatedDelta = session.accept(legacyDeltaLine())
        val firstCell = session.accept(legacyCellLine())
        val repeatedCell = session.accept(legacyCellLine())

        assertNotNull(firstDelta.legacyDeltaEvent)
        assertNull(repeatedDelta.legacyDeltaEvent)
        assertNotNull(firstCell.legacyCellEvent)
        assertNull(repeatedCell.legacyCellEvent)
        session.close()
    }

    private fun newSession(
        nowMillis: () -> Long = { 1_000L },
        onContextCaptured: () -> Unit = {}
    ): ProbeStreamSession {
        return ProbeStreamSession(
            contextProvider = {
                onContextCaptured()
                ProbeAttemptContext(
                    sessionDbId = 1L,
                    probeRunId = 2L,
                    victim = "victim",
                    moving = false,
                    network = ProbeNetworkSnapshot(null, null, null, null)
                )
            },
            nowMillis = nowMillis
        )
    }

    private fun streamReadyLine(): String {
        return """{"contract":"cellidtracker.probe","version":1,"event":"stream_ready"}"""
    }

    private fun startedLine(): String {
        return """{"contract":"cellidtracker.probe","version":1,"event":"attempt_started","attempt_id":"call-1","invite_elapsed_ms":100,"invite_unix_ms":1000}"""
    }

    private fun provisionalLine(): String {
        return """{"contract":"cellidtracker.probe","version":1,"event":"provisional_received","attempt_id":"call-1","status":183,"delta_ms":500,"invite_elapsed_ms":100,"response_elapsed_ms":600,"invite_unix_ms":1000,"response_unix_ms":1500}"""
    }

    private fun cellLine(): String {
        return """{"contract":"cellidtracker.probe","version":1,"event":"cell_observed","attempt_id":"call-1","status":183,"delta_ms":500,"invite_elapsed_ms":100,"response_elapsed_ms":600,"invite_unix_ms":1000,"response_unix_ms":1500,"mcc":466,"mnc":92,"lac":13700,"cid":81261593}"""
    }

    private fun finishedLine(): String {
        return """{"contract":"cellidtracker.probe","version":1,"event":"attempt_finished","attempt_id":"call-1","finished_unix_ms":2000,"outcome":"completed","reason":"rollover"}"""
    }

    private fun legacyDeltaLine(): String {
        return "[intercarrier] status=183 delta_ms=500 invite=100 pr=600"
    }

    private fun legacyCellLine(): String {
        return "[probe_event] call_id=call-1 status=183 delta_ms=500 invite_ms=100 pr_ms=600 mcc=466 mnc=92 lac=13700 cid=81261593"
    }
}
