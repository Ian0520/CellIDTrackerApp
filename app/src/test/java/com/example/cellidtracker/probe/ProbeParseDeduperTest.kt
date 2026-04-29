package com.example.cellidtracker.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParseDeduperTest {
    @Test
    fun rejectsSameTowerInsideSameCycle() {
        val state = ProbeParseDeduperState()
        val parsed = ParsedCellFromLog(mcc = 466, mnc = 92, lac = 13700, cid = 81261592)

        assertTrue(shouldAcceptParsedCell(parsed, state = state))
        assertFalse(shouldAcceptParsedCell(parsed, state = state))
    }

    @Test
    fun acceptsSameTowerAfterBoundaryMarker() {
        val state = ProbeParseDeduperState()
        val parsed = ParsedCellFromLog(mcc = 466, mnc = 92, lac = 13700, cid = 81261592)

        assertTrue(shouldAcceptParsedCell(parsed, state = state))
        markProbeCycleBoundary(state)
        assertTrue(shouldAcceptParsedCell(parsed, state = state))
    }

    @Test
    fun rejectsSecondTowerInsideSameCycle() {
        val state = ProbeParseDeduperState()
        val first = ParsedCellFromLog(mcc = 466, mnc = 92, lac = 13700, cid = 81261592)
        val second = ParsedCellFromLog(mcc = 466, mnc = 92, lac = 13700, cid = 81261593)

        assertTrue(shouldAcceptParsedCell(first, state = state))
        assertFalse(shouldAcceptParsedCell(second, state = state))
    }

    @Test
    fun parsesDeltaOnlyIntercarrierLineWithStatus() {
        val event = tryParseProbeDeltaEventFromStdoutLine(
            "[intercarrier] status=183 delta_ms=512 invite=12345 pr=12857"
        )

        requireNotNull(event)
        assertEquals(183, event.status)
        assertEquals(512L, event.deltaMs)
        assertEquals(12345L, event.inviteMs)
        assertEquals(12857L, event.prMs)
    }

    @Test
    fun parsesLegacyDeltaOnlyIntercarrierLineWithoutStatus() {
        val event = tryParseProbeDeltaEventFromStdoutLine(
            "[intercarrier] delta_ms=701 invite=12345 pr=13046"
        )

        requireNotNull(event)
        assertNull(event.status)
        assertEquals(701L, event.deltaMs)
        assertEquals(12345L, event.inviteMs)
        assertEquals(13046L, event.prMs)
    }
}
