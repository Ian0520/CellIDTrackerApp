package com.example.cellidtracker.experiment

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeCaptureExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sanitizer = CaptureSanitizer(CampaignPseudonymizer(ByteArray(32) { it.toByte() }))

    @Test
    fun rotationBoundaryRetainsCompleteBodyAndOriginalPacketReferences() {
        val source = temporary.newFolder("raw")
        val destination = File(temporary.root, "sanitized")
        val body = "v=0\r\nc=IN IP4 192.0.2.25\r\nm=audio 40000 RTP/AVP 0\r\n"
        val message = ("SIP/2.0 183 Session Progress\r\n" +
            "Call-ID: private-call@example.test\r\nCSeq: 1 INVITE\r\n" +
            "Content-Type: application/sdp\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray()
        val cut = message.size - 20
        File(source, "sip_capture.jsonl.1").writeText(packet(message.copyOfRange(0, cut), 1, 100).toString() + "\n")
        File(source, "sip_capture.jsonl").writeText(packet(message.copyOfRange(cut, message.size), 2, 100 + cut.toLong()).toString() + "\n")
        val originalFiles = source.listFiles()!!.associate { it.name to it.readText() }

        val result = exportNativeCapture(source, destination, sanitizer)

        assertEquals(2L, result.packetRecords)
        assertEquals(1L, result.reassembly.completeMessages)
        assertEquals(0L, result.reassembly.incompleteStreams)
        assertEquals(0L, result.sanitizationFailures)
        val reconstructed = JSONObject(File(destination, "sip_messages.sanitized.jsonl").readLines().single())
        assertEquals(listOf(1L, 2L), reconstructed.getJSONArray("contributing_sequences").let { a ->
            (0 until a.length()).map { a.getLong(it) }
        })
        val cleanBody = String(Base64.getDecoder().decode(reconstructed.getString("body_base64")), StandardCharsets.ISO_8859_1)
        assertTrue(cleanBody.contains("m=audio 40000 RTP/AVP 0"))
        assertFalse(cleanBody.contains("192.0.2.25"))
        assertTrue(reconstructed.getBoolean("complete"))
        assertEquals(1001L, reconstructed.getLong("first_byte_unix_ms"))
        assertEquals(1002L, reconstructed.getLong("completed_unix_ms"))
        assertEquals(originalFiles, source.listFiles()!!.associate { it.name to it.readText() })
        val firstPacket = JSONObject(File(destination, "sip_capture.sanitized.jsonl.1").readLines().single())
        assertFalse(firstPacket.getBoolean("complete"))
        assertFalse(firstPacket.has("headers"))
    }

    @Test
    fun xmlPseudoheaderLeakIsRemovedAndUnresolvedDatagramIsCounted() {
        val source = temporary.newFolder("raw")
        val destination = File(temporary.root, "sanitized")
        val user = "999888777666555"
        val bytes = "x=\"1\">\r\n      <uri>sip:$user@example.test</uri>\r\n</reginfo>".toByteArray()
        val record = packet(bytes, 1, 0).put("transport", "udp")
            .put("headers", JSONArray().put(JSONObject().put("name", "      <uri>sip").put("value", "$user@example.test</uri>")))
        File(source, "sip_capture.jsonl").writeText(record.toString() + "\n")

        val result = exportNativeCapture(source, destination, sanitizer)

        assertEquals(1L, result.reassembly.incompleteDatagrams)
        assertEquals(0L, result.reassembly.completeMessages)
        result.files.forEach { assertFalse(it.readText().contains(user)) }
        val clean = JSONObject(File(destination, "sip_capture.sanitized.jsonl").readLines().single())
        assertFalse(clean.has("headers"))
        assertFalse(clean.has("start_line"))
        assertTrue(clean.isNull("payload_base64"))
    }

    @Test
    fun retryRemovesStaleExportsAndMalformedRecordErrorsContainNoInputText() {
        val source = temporary.newFolder("raw")
        val destination = temporary.newFolder("sanitized")
        File(destination, "sip_capture.sanitized.jsonl.7").writeText("old private export")
        File(source, "sip_capture.jsonl").writeText("not-json-private-identifier\n")

        val result = exportNativeCapture(source, destination, sanitizer)

        assertEquals(1L, result.sanitizationFailures)
        assertFalse(File(destination, "sip_capture.sanitized.jsonl.7").exists())
        result.files.forEach { assertFalse(it.readText().contains("private-identifier")) }
    }

    @Test
    fun nativePacketQualityEventsAreVisibleWithoutNativeRecorderDrops() {
        val source = temporary.newFolder("raw")
        val destination = File(temporary.root, "sanitized")
        val event = JSONObject().put("record_type", "runtime_event")
            .put("sequence", 1).put("run_id", "run").put("process_id", 10)
            .put("event", "packet_incomplete").put("detail", "protocol=udp;captured_bytes=20;declared_bytes=40")
        File(source, "sip_capture.jsonl").writeText(event.toString() + "\n")

        val result = exportNativeCapture(source, destination, sanitizer)

        assertEquals(1L, result.packetQualityIssues)
        assertEquals(0L, result.reportedNativeDroppedEvents)
    }

    private fun packet(bytes: ByteArray, number: Long, sequence: Long) = JSONObject()
        .put("record_type", "transport_payload").put("run_id", "run").put("process_id", 10)
        .put("sequence", number).put("unix_ms", 1000 + number).put("elapsed_ns", number * 1_000_000)
        .put("direction", "incoming").put("capture_point", "decoded_transport").put("transport", "tcp")
        .put("ip_version", 4).put("source_address", "192.0.2.1").put("destination_address", "192.0.2.2")
        .put("source_port", 5060).put("destination_port", 4000).put("tcp_sequence", sequence)
        .put("recognized_sip", true).put("payload_base64", Base64.getEncoder().encodeToString(bytes))
}
