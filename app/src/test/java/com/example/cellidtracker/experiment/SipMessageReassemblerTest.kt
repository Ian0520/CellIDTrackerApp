package com.example.cellidtracker.experiment

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipMessageReassemblerTest {
    private val output = mutableListOf<JSONObject>()
    private val reassembler = SipMessageReassembler(output::add)
    private var nativeSequence = 0L

    private fun message(body: String = "", status: Int = 183): ByteArray =
        ("SIP/2.0 $status Session Progress\r\n" +
            "Via: SIP/2.0/TCP 192.0.2.1;branch=branch-one\r\n" +
            "Call-ID: test-call@example.test\r\nCSeq: 12 INVITE\r\n" +
            "Content-Type: application/sdp\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
            .toByteArray(StandardCharsets.ISO_8859_1)

    private fun packet(bytes: ByteArray, sequence: Long, time: Long = nativeSequence + 1): JSONObject =
        JSONObject()
            .put("capture_format_version", 1).put("record_type", "transport_payload")
            .put("run_id", "test-run").put("process_id", 10).put("sequence", ++nativeSequence)
            .put("unix_ms", 1000 + time).put("elapsed_ns", time * 1_000_000)
            .put("direction", "incoming").put("capture_point", "decoded_transport")
            .put("transport", "tcp").put("ip_version", 4)
            .put("source_address", "192.0.2.2").put("destination_address", "192.0.2.1")
            .put("source_port", 5060).put("destination_port", 4000)
            .put("tcp_sequence", sequence and 0xffffffffL)
            .put("payload_base64", Base64.getEncoder().encodeToString(bytes))

    private fun payload(record: JSONObject): ByteArray = Base64.getDecoder().decode(record.getString("payload_base64"))

    @Test
    fun splitResponseReconstructsExactBodyAndBothTimingBoundaries() {
        val bytes = message("v=0\r\na=cell-id:123456789\r\n")
        val cut = bytes.size - 8
        reassembler.accept(packet(bytes.copyOfRange(0, cut), 500, 10))
        reassembler.accept(packet(bytes.copyOfRange(cut, bytes.size), 500 + cut.toLong(), 20))
        val summary = reassembler.finish()
        assertEquals(1L, summary.completeMessages)
        assertEquals(0L, summary.incompleteStreams)
        val reconstructed = output.single()
        assertArrayEquals(bytes, payload(reconstructed))
        assertEquals("sip_message", reconstructed.getString("record_type"))
        assertTrue(reconstructed.getBoolean("complete"))
        assertEquals(1010L, reconstructed.getLong("first_byte_unix_ms"))
        assertEquals(1020L, reconstructed.getLong("completed_unix_ms"))
        assertEquals(2, reconstructed.getJSONArray("contributing_sequences").length())
        assertEquals("test-call@example.test", reconstructed.getString("call_id"))
        assertEquals("branch-one", reconstructed.getString("via_branch"))
    }

    @Test
    fun reorderedPacketsUseSequencePositionsAndActualCompletionTime() {
        val bytes = message("body")
        val cut = 70
        reassembler.accept(packet(bytes.copyOfRange(cut, bytes.size), 1000 + cut.toLong(), 10))
        reassembler.accept(packet(bytes.copyOfRange(0, cut), 1000, 20))
        assertEquals(1L, reassembler.finish().completeMessages)
        assertArrayEquals(bytes, payload(output.single()))
        assertEquals(1020L, output.single().getLong("first_byte_unix_ms"))
        assertEquals(1020L, output.single().getLong("completed_unix_ms"))
    }

    @Test
    fun coalescedMessagesAreSeparatedByTheirOwnContentLengths() {
        val first = message("first")
        val second = message("second", 200)
        reassembler.accept(packet(first + second, 600))
        assertEquals(2L, reassembler.finish().completeMessages)
        assertArrayEquals(first, payload(output[0]))
        assertArrayEquals(second, payload(output[1]))
    }

    @Test
    fun identicalRetransmissionIsDeduplicatedWithoutDelayingCompletion() {
        val bytes = message("body")
        reassembler.accept(packet(bytes, 600, 10))
        reassembler.accept(packet(bytes, 600, 80))
        val summary = reassembler.finish()
        assertEquals(1L, summary.completeMessages)
        assertEquals(1L, summary.duplicateTcpPackets)
        assertEquals(bytes.size.toLong(), summary.duplicateTcpBytes)
        assertEquals(1010L, output.single().getLong("completed_unix_ms"))
    }

    @Test
    fun consistentPartialOverlapIsAccepted() {
        val bytes = message("body")
        reassembler.accept(packet(bytes.copyOfRange(0, 120), 500))
        reassembler.accept(packet(bytes.copyOfRange(100, bytes.size), 600))
        val summary = reassembler.finish()
        assertEquals(1L, summary.completeMessages)
        assertEquals(20L, summary.duplicateTcpBytes)
        assertArrayEquals(bytes, payload(output.single()))
    }

    @Test
    fun conflictingOverlapDiscardsEntireAmbiguousStream() {
        val bytes = message("body")
        reassembler.accept(packet(bytes, 500))
        val changed = bytes.copyOf().also { it[5] = 'X'.code.toByte() }
        reassembler.accept(packet(changed, 500))
        val summary = reassembler.finish()
        assertEquals(0L, summary.completeMessages)
        assertEquals(1L, summary.conflictingTcpStreams)
        assertEquals(1L, summary.incompleteStreams)
        assertTrue(output.isEmpty())
    }

    @Test
    fun missingBytesAreNotFilledFromSimilarText() {
        val bytes = message("body")
        reassembler.accept(packet(bytes.copyOfRange(0, 60), 100))
        reassembler.accept(packet(bytes.copyOfRange(61, bytes.size), 161))
        val summary = reassembler.finish()
        assertEquals(0L, summary.completeMessages)
        assertEquals(1L, summary.tcpGaps)
        assertEquals(1L, summary.incompleteStreams)
    }

    @Test
    fun tcpSequenceWrapIsUnwrappedBeforeJoining() {
        val bytes = message("body")
        val cut = 60
        val firstSequence = 0xfffffff0L
        reassembler.accept(packet(bytes.copyOfRange(0, cut), firstSequence))
        reassembler.accept(packet(bytes.copyOfRange(cut, bytes.size), firstSequence + cut))
        assertEquals(1L, reassembler.finish().completeMessages)
        assertArrayEquals(bytes, payload(output.single()))
    }

    @Test
    fun oppositeDirectionsAndDifferentTuplesCannotCompleteEachOther() {
        val bytes = message("body")
        reassembler.accept(packet(bytes.copyOfRange(0, 60), 500))
        reassembler.accept(packet(bytes.copyOfRange(60, bytes.size), 560).put("direction", "outgoing"))
        reassembler.accept(packet(bytes.copyOfRange(60, bytes.size), 560).put("source_port", 9999))
        val summary = reassembler.finish()
        assertEquals(0L, summary.completeMessages)
        assertEquals(3L, summary.incompleteStreams)
    }

    @Test
    fun applicationSendBoundariesNeverStitchWithDecodedPackets() {
        val bytes = message("body")
        reassembler.accept(packet(bytes.copyOfRange(0, 60), 500).put("capture_point", "application_send"))
        reassembler.accept(packet(bytes.copyOfRange(60, bytes.size), 560))
        val summary = reassembler.finish()
        assertEquals(0L, summary.completeMessages)
        assertEquals(1L, summary.incompleteDatagrams)
        assertEquals(1L, summary.incompleteStreams)
    }

    @Test
    fun applicationSendAndUdpAcceptOnlyCompleteMessages() {
        val bytes = message("body")
        reassembler.accept(packet(bytes, 500).put("capture_point", "application_send"))
        reassembler.accept(packet(bytes, 900).put("transport", "udp"))
        reassembler.accept(packet(bytes.copyOfRange(0, 60), 1000).put("transport", "udp"))
        val summary = reassembler.finish()
        assertEquals(2L, summary.completeMessages)
        assertEquals(1L, summary.incompleteDatagrams)
    }

    @Test
    fun sequenceDiscontinuityPreventsCrossingMissingNativeRecord() {
        val bytes = message("body")
        reassembler.accept(packet(bytes.copyOfRange(0, 60), 500))
        nativeSequence++
        reassembler.accept(packet(bytes.copyOfRange(60, bytes.size), 560))
        val summary = reassembler.finish()
        assertEquals(0L, summary.completeMessages)
        assertEquals(1L, summary.nativeDiscontinuities)
        assertEquals(2L, summary.incompleteStreams)
    }

    @Test
    fun idleTimeoutPreventsJoiningAcrossPotentialConnectionReuse() {
        val bytes = message("body")
        reassembler.accept(packet(bytes.copyOfRange(0, 60), 500, 1))
        reassembler.accept(packet(bytes.copyOfRange(60, bytes.size), 560, 70_000))
        val summary = reassembler.finish()
        assertEquals(0L, summary.completeMessages)
        assertEquals(2L, summary.incompleteStreams)
    }

    @Test
    fun xmlContinuationCannotBecomeSipHeaderBlock() {
        val fragment = "<contact>sip:123456789012345@example.test</contact>\r\n\r\n".toByteArray()
        reassembler.accept(packet(fragment, 500))
        val summary = reassembler.finish()
        assertEquals(0L, summary.completeMessages)
        assertEquals(1L, summary.malformedMessages)
        assertFalse(summary.toJson().toString().contains("123456789012345"))
    }

    @Test
    fun missingTcpContentLengthAndConflictingLengthsAreRejected() {
        val absent = "SIP/2.0 200 OK\r\nCall-ID: secret\r\n\r\nbody".toByteArray()
        reassembler.accept(packet(absent, 500))
        assertEquals(1L, reassembler.finish().missingContentLength)
        assertNull(SipMessageFraming.parseComplete("SIP/2.0 200 OK\r\nContent-Length: 0\r\nl: 0\r\n\r\n".toByteArray()))
        assertNull(SipMessageFraming.parseComplete("SIP/2.0 200 OK\r\nContent-Length: -1\r\n\r\n".toByteArray()))
    }

    @Test
    fun strictFramingSupportsCompactLengthAndFoldingButRejectsTrailingBytes() {
        val bytes = "INVITE sips:research@example.test SIP/2.0\r\nSubject: one\r\n two\r\nl: 4\r\n\r\nbody".toByteArray()
        val parsed = SipMessageFraming.parseComplete(bytes)
        assertNotNull(parsed)
        assertEquals("one two", parsed!!.headers.first().second)
        assertEquals("INVITE", parsed.method)
        assertNull(SipMessageFraming.parseComplete(bytes + "extra".toByteArray()))
        assertNull(SipMessageFraming.parseComplete(bytes.copyOfRange(0, bytes.size - 1)))
        assertNotNull(SipMessageFraming.parseComplete("SIP/2.0 200 OK\r\n\r\nbody".toByteArray(), false))
        assertNull(SipMessageFraming.parseComplete("garbage\r\nSIP/2.0 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()))
    }

    @Test
    fun completePrefixSurvivesAnIncompleteFinalMessageWithoutInventingItsBody() {
        val first = message("first")
        val second = message("unfinished")
        reassembler.accept(packet(first + second.copyOfRange(0, second.size - 3), 500))
        val summary = reassembler.finish()
        assertEquals(1L, summary.completeMessages)
        assertEquals(1L, summary.incompleteStreams)
        assertArrayEquals(first, payload(output.single()))
    }

    @Test
    fun oversizedRecordFailsClosedWithAnExplicitLimitCount() {
        reassembler.accept(packet(ByteArray(1024 * 1024 + 8) { 'x'.code.toByte() }, 500))
        val summary = reassembler.finish()
        assertEquals(0L, summary.completeMessages)
        assertTrue(summary.limitExceeded > 0)
        assertTrue(output.isEmpty())
    }

    @Test
    fun explicitTcpBoundaryPreventsOldPartialMessageFromJoiningReusedTuple() {
        val old = message("old-body")
        val fresh = message("new-body", 200)
        reassembler.accept(packet(old.copyOfRange(0, 60), 500))
        reassembler.accept(JSONObject()
            .put("record_type", "runtime_event").put("run_id", "test-run")
            .put("process_id", 10).put("sequence", ++nativeSequence)
            .put("event", "tcp_connection_boundary").put("detail", "kind=syn"))
        // The same tuple and even the same byte position now belong to a distinct connection.
        reassembler.accept(packet(fresh, 500))
        val summary = reassembler.finish()
        assertEquals(1L, summary.completeMessages)
        assertEquals(1L, summary.incompleteStreams)
        assertEquals(1L, summary.connectionBoundaryEvents)
        assertEquals(0L, summary.conflictingTcpStreams)
        assertEquals(1L, output.single().getLong("connection_epoch"))
        assertArrayEquals(fresh, payload(output.single()))
    }

    @Test
    fun explicitBoundaryAllowsNewConnectionAfterAnAmbiguousOldStream() {
        val bytes = message("body")
        reassembler.accept(packet(bytes, 500))
        reassembler.accept(packet(bytes.copyOf().also { it[5] = 'X'.code.toByte() }, 500))
        reassembler.accept(JSONObject()
            .put("record_type", "runtime_event").put("run_id", "test-run")
            .put("process_id", 10).put("sequence", ++nativeSequence)
            .put("event", "tcp_connection_boundary").put("detail", "kind=rst"))
        reassembler.accept(packet(bytes, 500))
        val summary = reassembler.finish()
        assertEquals(1L, summary.completeMessages)
        assertEquals(1L, summary.conflictingTcpStreams)
        assertEquals(1L, summary.connectionBoundaryEvents)
    }

    @Test
    fun finishIsIdempotentAndDoesNotReemitMessages() {
        reassembler.accept(packet(message(), 500))
        assertEquals(reassembler.finish(), reassembler.finish())
        assertEquals(1, output.size)
    }
}
