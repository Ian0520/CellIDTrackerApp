package com.example.cellidtracker.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParseDeduperTest {
    @Test
    fun rejectsSameTowerInsideWindow() {
        val state = ProbeParseDeduperState()
        val parsed = ParsedCellFromLog(mcc = 466, mnc = 92, lac = 13700, cid = 81261592)

        assertTrue(shouldAcceptParsedCell(parsed, nowMillis = 1_000L, state = state, duplicateWindowMillis = 10_000L))
        assertFalse(shouldAcceptParsedCell(parsed, nowMillis = 2_000L, state = state, duplicateWindowMillis = 10_000L))
    }

    @Test
    fun acceptsSameTowerAfterWindow() {
        val state = ProbeParseDeduperState()
        val parsed = ParsedCellFromLog(mcc = 466, mnc = 92, lac = 13700, cid = 81261592)

        assertTrue(shouldAcceptParsedCell(parsed, nowMillis = 1_000L, state = state, duplicateWindowMillis = 3_000L))
        assertTrue(shouldAcceptParsedCell(parsed, nowMillis = 4_500L, state = state, duplicateWindowMillis = 3_000L))
    }

    @Test
    fun acceptsDifferentTowerInsideWindow() {
        val state = ProbeParseDeduperState()
        val first = ParsedCellFromLog(mcc = 466, mnc = 92, lac = 13700, cid = 81261592)
        val second = ParsedCellFromLog(mcc = 466, mnc = 92, lac = 13700, cid = 81261593)

        assertTrue(shouldAcceptParsedCell(first, nowMillis = 1_000L, state = state, duplicateWindowMillis = 10_000L))
        assertTrue(shouldAcceptParsedCell(second, nowMillis = 1_500L, state = state, duplicateWindowMillis = 10_000L))
    }
}
