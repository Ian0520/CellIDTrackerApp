package com.example.cellidtracker.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeEventParserTest {
    @Test
    fun parsesCurrentNativeProbeEvent() {
        val event = tryParseProbeEventFromStdoutLine(
            "[probe_event] call_id=phase0-call-001 status=183 delta_ms=749 " +
                "invite_ms=799870333 pr_ms=799871082 mcc=466 mnc=92 lac=13700 cid=81261593"
        )

        requireNotNull(event)
        assertEquals("phase0-call-001", event.callId)
        assertEquals(183, event.status)
        assertEquals(749L, event.deltaMs)
        assertEquals(799870333L, event.inviteMs)
        assertEquals(799871082L, event.prMs)
        assertEquals(ParsedCellFromLog(466, 92, 13700, 81261593), event.parsedCell)
    }

    @Test
    fun parsesCurrentDeltaEventWithStatus() {
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
    fun keepsSupportingLegacyDeltaEventWithoutStatus() {
        val event = tryParseProbeDeltaEventFromStdoutLine(
            "[intercarrier] delta_ms=701 invite=12345 pr=13046"
        )

        requireNotNull(event)
        assertNull(event.status)
        assertEquals(701L, event.deltaMs)
        assertEquals(12345L, event.inviteMs)
        assertEquals(13046L, event.prMs)
    }

    @Test
    fun parsesSanitizedNativeOutputFixtureExactlyOnce() {
        val lines = requireNotNull(javaClass.getResource("/probe/native_stdout_sample.txt"))
            .readText()
            .lineSequence()
            .toList()

        val probeEvents = lines.mapNotNull(::tryParseProbeEventFromStdoutLine)
        val deltaEvents = lines.mapNotNull(::tryParseProbeDeltaEventFromStdoutLine)

        assertEquals(1, probeEvents.size)
        assertEquals(1, deltaEvents.size)
        assertEquals(probeEvents.single().deltaMs, deltaEvents.single().deltaMs)
        assertEquals(183, probeEvents.single().status)
    }

    @Test
    fun rejectsIncompleteOrUnrelatedLines() {
        assertNull(tryParseProbeEventFromStdoutLine("183: Session Progress"))
        assertNull(
            tryParseProbeEventFromStdoutLine(
                "[probe_event] call_id=missing-fields status=183 delta_ms=500"
            )
        )
        assertNull(tryParseProbeDeltaEventFromStdoutLine("[intercarrier] delta_ms=unknown"))
    }
}
