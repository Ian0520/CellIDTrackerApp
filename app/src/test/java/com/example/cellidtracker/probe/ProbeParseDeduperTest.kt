package com.example.cellidtracker.probe

import org.junit.Assert.assertFalse
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
}
