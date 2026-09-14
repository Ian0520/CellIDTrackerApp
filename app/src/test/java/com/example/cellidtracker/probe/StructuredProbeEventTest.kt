package com.example.cellidtracker.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StructuredProbeEventTest {
    @Test
    fun parsesEveryVersionOneEvent() {
        assertEquals(
            ProbeStreamReadyEvent(1),
            parse("""{"contract":"cellidtracker.probe","version":1,"event":"stream_ready"}""")
        )
        assertEquals(
            ProbeAttemptStartedEvent(1, "call-1", 100, 1_700_000_000_000),
            parse(
                """{"contract":"cellidtracker.probe","version":1,"event":"attempt_started","attempt_id":"call-1","invite_elapsed_ms":100,"invite_unix_ms":1700000000000}"""
            )
        )
        assertEquals(
            ProbeProvisionalReceivedEvent(
                1,
                "call-1",
                183,
                749,
                100,
                849,
                1_700_000_000_000,
                1_700_000_000_749
            ),
            parse(provisionalJson())
        )
        assertEquals(
            ProbeCellObservedEvent(
                1,
                "call-1",
                183,
                749,
                100,
                849,
                1_700_000_000_000,
                1_700_000_000_749,
                ParsedCellFromLog(466, 92, 13_700, 81_261_593)
            ),
            parse(cellJson())
        )
        assertEquals(
            ProbeAttemptFinishedEvent(
                1,
                "call-1",
                1_700_000_001_000,
                "cell_observed",
                "next_invite"
            ),
            parse(
                """{"contract":"cellidtracker.probe","version":1,"event":"attempt_finished","attempt_id":"call-1","finished_unix_ms":1700000001000,"outcome":"cell_observed","reason":"next_invite"}"""
            )
        )
    }

    @Test
    fun rejectsOtherJsonVersionsAndInconsistentTiming() {
        assertNull(tryParseStructuredProbeEventFromStdoutLine("183: Session Progress"))
        assertNull(
            tryParseStructuredProbeEventFromStdoutLine(
                """{"location":{"lat":25.0,"lng":121.0}}"""
            )
        )
        assertNull(
            tryParseStructuredProbeEventFromStdoutLine(
                """{"contract":"cellidtracker.probe","version":2,"event":"stream_ready"}"""
            )
        )
        assertNull(
            tryParseStructuredProbeEventFromStdoutLine(
                provisionalJson().replace("\"delta_ms\":749", "\"delta_ms\":500")
            )
        )
    }

    private fun parse(line: String): StructuredProbeEvent {
        return requireNotNull(tryParseStructuredProbeEventFromStdoutLine(line))
    }

    private fun provisionalJson(): String {
        return """{"contract":"cellidtracker.probe","version":1,"event":"provisional_received","attempt_id":"call-1","status":183,"delta_ms":749,"invite_elapsed_ms":100,"response_elapsed_ms":849,"invite_unix_ms":1700000000000,"response_unix_ms":1700000000749}"""
    }

    private fun cellJson(): String {
        return """{"contract":"cellidtracker.probe","version":1,"event":"cell_observed","attempt_id":"call-1","status":183,"delta_ms":749,"invite_elapsed_ms":100,"response_elapsed_ms":849,"invite_unix_ms":1700000000000,"response_unix_ms":1700000000749,"mcc":466,"mnc":92,"lac":13700,"cid":81261593}"""
    }
}
