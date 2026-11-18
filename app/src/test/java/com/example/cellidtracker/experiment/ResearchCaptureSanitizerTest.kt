package com.example.cellidtracker.experiment

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchCaptureSanitizerTest {
    @Test
    fun authenticationChallengesAreRedactedInEveryExportedView() {
        val wire = listOf(
            "SIP/2.0 401 Unauthorized",
            "WWW-Authenticate: Digest realm=example.test, nonce=private-server-nonce",
            "Proxy-Authenticate: Digest nonce=private-proxy-nonce, opaque=private-opaque",
            "Content-Length: 0", "", ""
        ).joinToString("\r\n")
        val record = JSONObject().put("record_type", "sip_message").put("transport", "tcp")
            .put("payload_base64", Base64.getEncoder().encodeToString(wire.toByteArray(StandardCharsets.ISO_8859_1)))

        val clean = sanitizer.sanitizeRecord(record)
        val payload = String(Base64.getDecoder().decode(clean.getString("payload_base64")), StandardCharsets.ISO_8859_1)
        assertTrue(payload.contains("WWW-Authenticate: <redacted>"))
        assertTrue(payload.contains("Proxy-Authenticate: <redacted>"))
        listOf("private-server-nonce", "private-proxy-nonce", "private-opaque").forEach { secret ->
            assertFalse(payload.contains(secret))
            assertFalse(clean.toString().contains(secret))
        }
        assertEquals("<redacted>", clean.getJSONArray("headers").getJSONObject(0).getString("value"))
        assertEquals("<redacted>", clean.getJSONArray("headers").getJSONObject(1).getString("value"))
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sanitizer = CaptureSanitizer(
        CampaignPseudonymizer(ByteArray(32) { index -> index.toByte() })
    )

    @Test
    fun textIdentifiersAreStableAndRemoved() {
        val source = "sip:+886900000000@192.0.2.10;branch=z9hG4bK-secret;tag=tag-one " +
            "[2001:db8::10] aa:bb:cc:dd:ee:ff " +
            "123e4567-e89b-12d3-a456-426614174000 AIzaabcdefghijklmnopqrstuvwxyz123456789"

        val first = sanitizer.sanitizeText(source)
        val second = sanitizer.sanitizeText(source)

        assertEquals(first, second)
        listOf(
            "+886900000000",
            "192.0.2.10",
            "2001:db8::10",
            "aa:bb:cc:dd:ee:ff",
            "z9hG4bK-secret",
            "tag-one",
            "123e4567-e89b-12d3-a456-426614174000",
            "AIza"
        ).forEach { secret -> assertFalse("leaked $secret", first.contains(secret)) }
        assertTrue(first.contains("sip-user-"))
        assertTrue(first.contains("ip-"))
    }

    @Test
    fun timingNumbersRemainUsableWhilePhoneNumbersAreRemoved() {
        val source =
            "unix_ms=1789526880123 elapsed_ms=42539245 " +
                "local=0900000000 intl=+886900000000"

        val sanitized = sanitizer.sanitizeText(source)

        assertTrue(sanitized.contains("unix_ms=1789526880123"))
        assertTrue(sanitized.contains("elapsed_ms=42539245"))
        assertFalse(sanitized.contains("0900000000"))
        assertFalse(sanitized.contains("+886900000000"))
    }

    @Test
    fun sipRecordRetainsShapeButRemovesCredentialsAndIdentifiers() {
        val body = "v=0\r\nc=IN IP6 2001:db8::20"
        val sip = listOf(
            "SIP/2.0 183 Session Progress",
            "Via: SIP/2.0/TCP 192.0.2.10;branch=z9hG4bK-secret",
            "Authorization: Digest username=\"+886900000000\", response=\"credential\"",
            " continued-secret",
            "Call-ID: call-secret@example.test",
            "From: <sip:+886900000000@example.test>;tag=from-secret",
            "To: <sip:+886911111111@example.test>;tag=to-secret",
            "P-Access-Network-Info: 3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=46692123456789AB",
            "Content-Type: application/sdp",
            "Content-Length: ${body.toByteArray(StandardCharsets.ISO_8859_1).size}", "", ""
        ).joinToString("\r\n") + body
        val record = JSONObject()
            .put("record_type", "transport_payload")
            .put("recognized_sip", true)
            .put("source_address", "192.0.2.10")
            .put("destination_address", "2001:db8::20")
            .put("call_id", "call-secret@example.test")
            .put("via_branch", "z9hG4bK-secret")
            .put("from_tag", "from-secret")
            .put("to_tag", "to-secret")
            .put("headers", JSONArray()
                .put(JSONObject().put("name", "Authorization").put("value", "credential"))
                .put(JSONObject().put("name", "Call-ID").put("value", "call-secret@example.test")))
            .put("payload_base64", Base64.getEncoder().encodeToString(
                sip.toByteArray(StandardCharsets.ISO_8859_1)
            ))
            .put("body_base64", Base64.getEncoder().encodeToString(
                body.toByteArray(StandardCharsets.ISO_8859_1)
            ))

        val sanitized = sanitizer.sanitizeRecord(record)
        val sanitizedSip = String(
            Base64.getDecoder().decode(sanitized.getString("payload_base64")),
            StandardCharsets.ISO_8859_1
        )
        val sanitizedBody = String(
            Base64.getDecoder().decode(sanitized.getString("body_base64")),
            StandardCharsets.ISO_8859_1
        )

        assertEquals("<redacted>", headerValue(sanitized, "Authorization"))
        assertEquals(8, sanitized.getJSONArray("headers").length())
        assertTrue(sanitizedSip.contains("Authorization: <redacted>"))
        listOf(
            "credential",
            "continued-secret",
            "+886900000000",
            "+886911111111",
            "192.0.2.10",
            "2001:db8::20",
            "call-secret@example.test",
            "z9hG4bK-secret",
            "from-secret",
            "to-secret"
        ).forEach { secret ->
            assertFalse("payload leaked $secret", sanitizedSip.contains(secret))
        }
        assertFalse(sanitizedBody.contains("2001:db8::20"))
        assertTrue(sanitizedSip.contains("utran-cell-id-3gpp=46692123456789AB"))
        assertTrue(sanitized.getBoolean("sanitized"))
    }

    @Test
    fun recognizedOutgoingInviteRetainsPayloadAndSdpWithIdentifiersRedacted() {
        val body = listOf(
            "v=0",
            "o=- 12345 67890 IN IP4 192.0.2.20",
            "s=-",
            "c=IN IP4 192.0.2.20",
            "t=0 0",
            "m=audio 40000 RTP/AVP 0 8 96",
            "a=rtpmap:96 AMR-WB/16000"
        ).joinToString("\r\n", postfix = "\r\n")
        val sip = listOf(
            "INVITE sip:+886911111111@example.test SIP/2.0",
            "Via: SIP/2.0/TCP 192.0.2.10:5060;rport;branch=z9hG4bK-request-secret",
            "From: <sip:+886900000000@example.test>;tag=caller-tag-secret",
            "To: <sip:+886911111111@example.test>",
            "Call-ID: outgoing-call-secret@example.test",
            "CSeq: 42 INVITE",
            "Authorization: Digest response=credential-secret",
            " continued-credential-secret",
            "Content-Type: application/sdp",
            "Content-Length: ${body.toByteArray(StandardCharsets.ISO_8859_1).size}",
            "", ""
        ).joinToString("\r\n") + body
        val record = JSONObject()
            .put("record_type", "transport_payload")
            .put("direction", "outgoing")
            .put("recognized_sip", true)
            .put("method", "INVITE")
            .put("call_id", "outgoing-call-secret@example.test")
            .put("via_branch", "z9hG4bK-request-secret")
            .put("cseq", "42")
            .put("cseq_method", "INVITE")
            .put("headers", JSONArray()
                .put(JSONObject().put("name", "Call-ID").put("value", "outgoing-call-secret@example.test"))
                .put(JSONObject().put("name", "Authorization").put("value", "credential-secret")))
            .put("payload_base64", Base64.getEncoder().encodeToString(sip.toByteArray(StandardCharsets.ISO_8859_1)))
            .put("body_base64", Base64.getEncoder().encodeToString(body.toByteArray(StandardCharsets.ISO_8859_1)))

        val sanitized = sanitizer.sanitizeRecord(record)
        val payload = String(Base64.getDecoder().decode(sanitized.getString("payload_base64")), StandardCharsets.ISO_8859_1)
        val sanitizedBody = String(Base64.getDecoder().decode(sanitized.getString("body_base64")), StandardCharsets.ISO_8859_1)
        assertTrue(sanitized.getBoolean("recognized_sip"))
        assertFalse(sanitized.optBoolean("unrecognizedPayloadRemoved"))
        assertEquals("INVITE", sanitized.getString("method"))
        assertEquals("42", sanitized.getString("cseq"))
        assertEquals("INVITE", sanitized.getString("cseq_method"))
        assertTrue(payload.startsWith("INVITE sip:"))
        assertTrue(payload.contains("Authorization: <redacted>"))
        assertTrue(payload.endsWith(sanitizedBody))
        assertTrue(sanitizedBody.contains("m=audio 40000 RTP/AVP 0 8 96"))
        assertTrue(sanitizedBody.contains("a=rtpmap:96 AMR-WB/16000"))
        assertTrue(sanitized.getString("call_id").isNotBlank())
        assertTrue(sanitized.getString("via_branch").isNotBlank())
        listOf(
            "+886900000000", "+886911111111", "192.0.2.10", "192.0.2.20",
            "outgoing-call-secret@example.test", "z9hG4bK-request-secret", "caller-tag-secret",
            "credential-secret", "continued-credential-secret"
        ).forEach { identifier ->
            assertFalse("payload leaked $identifier", payload.contains(identifier))
            assertFalse("body leaked $identifier", sanitizedBody.contains(identifier))
            assertFalse("record leaked $identifier", sanitized.toString().contains(identifier))
        }
    }

    @Test
    fun sipsRequestAndCompactHeadersAreRedactedCaseInsensitively() {
        val sip = listOf(
            "INVITE sips:private-user@example.test SIP/2.0",
            "Via: SIP/2.0/TCP example.test;rport;BRANCH = private-branch",
            "From: <sips:private-user@example.test>;tag = private-tag",
            "I : private-call-id@example.test",
            "Authorization : Digest response=private-credential",
            " continued-private-credential",
            "Content-Length: 0", "", ""
        ).joinToString("\r\n")
        val record = JSONObject()
            .put("record_type", "transport_payload")
            .put("recognized_sip", true)
            .put("headers", JSONArray()
                .put(JSONObject().put("name", "I ").put("value", "private-call-id@example.test"))
                .put(JSONObject().put("name", "Authorization ").put("value", "private-credential"))
                .put(JSONObject().put("name", "Via").put("value", "SIP/2.0/TCP example.test;rport;BRANCH = private-branch"))
                .put(JSONObject().put("name", "From").put("value", "<sips:private-user@example.test>;tag = private-tag")))
            .put("payload_base64", Base64.getEncoder().encodeToString(sip.toByteArray(StandardCharsets.ISO_8859_1)))
        val sanitized = sanitizer.sanitizeRecord(record)
        val payload = String(Base64.getDecoder().decode(sanitized.getString("payload_base64")), StandardCharsets.ISO_8859_1)
        assertTrue(payload.startsWith("INVITE sips:sip-user-"))
        assertTrue(payload.contains("Authorization: <redacted>"))
        assertTrue(payload.contains("I: call-id-"))
        assertTrue(payload.contains("BRANCH = sip-parameter-"))
        assertTrue(payload.contains("tag = sip-parameter-"))
        assertEquals("<redacted>", headerValue(sanitized, "Authorization"))
        listOf("private-user", "private-call-id@example.test", "private-credential", "continued-private-credential", "private-branch", "private-tag").forEach {
            assertFalse(payload.contains(it))
            assertFalse(sanitized.toString().contains(it))
        }
    }

    @Test
    fun unrecognizedPayloadIsNotExported() {
        val record = JSONObject()
            .put("record_type", "transport_payload")
            .put("recognized_sip", false)
            .put("payload_base64", Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3)))

        val sanitized = sanitizer.sanitizeRecord(record)

        assertTrue(sanitized.isNull("payload_base64"))
        assertTrue(sanitized.getBoolean("unrecognizedPayloadRemoved"))
        assertNull(sanitized.optString("payload_base64", null))
    }

    @Test
    fun xmlContinuationCannotLeakThroughInventedHeaderOrStartLine() {
        val identifier = "310260123456789"
        val fragment = "<uri>sip:$identifier@example.test</uri>\r\n</user>"
        val record = captureRecord(fragment)
            .put("transport", "udp")
            .put("recognized_sip", false)
            .put("sequence", 10)
            .put("start_line", fragment)
            .put("reason", identifier)
            .put("headers", JSONArray().put(JSONObject()
                .put("name", "<uri>sip")
                .put("value", "$identifier@example.test</uri>")))
            .put("unexpected_text", identifier)
            .put("unexpected_object", JSONObject().put(identifier, identifier))

        val sanitized = sanitizer.sanitizeRecord(record)

        assertEquals(10, sanitized.getInt("sequence"))
        assertEquals("decoded_transport", sanitized.getString("capture_point"))
        assertFalse(sanitized.toString().contains(identifier))
        listOf("headers", "start_line", "reason", "unexpected_text", "unexpected_object").forEach {
            assertFalse("retained unvalidated $it", sanitized.has(it))
        }
        assertTrue(sanitized.isNull("payload_base64"))
        assertTrue(sanitized.isNull("body_base64"))
        assertFalse(sanitized.getBoolean("recognized_sip"))
    }

    @Test
    fun recognizedFlagCannotMakeTruncatedRegisterOrResponseSafe() {
        val payloads = listOf(
            "REGISTER sip:example.test SIP/2.0\r\nAuthorization: private-credential",
            "SIP/2.0 183 Session Progress\r\nContent-Length: 20\r\n\r\n<uri>sip:private",
            "SIP/2.0 183 Session Progress\r\nContent-Length: 0\r\n\r\ntrailing-fragment"
        )
        payloads.forEach { payload ->
            val record = captureRecord(payload).put("recognized_sip", true)
                .put("start_line", "private-identifier")
                .put("headers", JSONArray().put(JSONObject()
                    .put("name", "Authorization").put("value", "private-credential")))
                .put("body_base64", encode("private-body"))

            val sanitized = sanitizer.sanitizeRecord(record)

            assertFalse(sanitized.getBoolean("recognized_sip"))
            assertFalse(sanitized.getBoolean("complete"))
            assertTrue(sanitized.isNull("payload_base64"))
            assertTrue(sanitized.isNull("body_base64"))
            assertFalse(sanitized.has("headers"))
            assertFalse(sanitized.toString().contains("private-"))
        }
    }

    @Test
    fun completeXmlBodyIsSanitizedAsBodyWithoutInterpretingItsLinesAsHeaders() {
        val identifier = "310260123456789"
        val body = "<user>\r\n<uri>sip:$identifier@example.test</uri>\r\n" +
            "Call-ID: ordinary-body-value\r\nAuthorization: ordinary-body-value\r\n</user>"
        val payload = listOf(
            "SIP/2.0 200 OK",
            "Call-ID: actual-header-value@example.test",
            "Content-Type: application/reginfo+xml",
            "Content-Length: ${body.toByteArray(StandardCharsets.ISO_8859_1).size}", "", ""
        ).joinToString("\r\n") + body
        val record = captureRecord(payload).put("record_type", "sip_message").put("complete", true)
            .put("headers", JSONArray().put(JSONObject().put("name", "<uri>sip").put("value", identifier)))
            .put("body_base64", encode("stale-private-body-copy"))

        val sanitized = sanitizer.sanitizeRecord(record)
        val safePayload = decode(sanitized, "payload_base64")
        val safeBody = decode(sanitized, "body_base64")

        assertTrue(sanitized.getBoolean("complete"))
        assertTrue(safeBody.contains("<uri>sip:sip-user-"))
        assertTrue(safeBody.contains("Call-ID: ordinary-body-value"))
        assertTrue(safeBody.contains("Authorization: ordinary-body-value"))
        assertTrue(safePayload.endsWith(safeBody))
        assertEquals(3, sanitized.getJSONArray("headers").length())
        assertEquals(safeBody.toByteArray(StandardCharsets.ISO_8859_1).size.toString(), headerValue(sanitized, "Content-Length"))
        listOf(identifier, "stale-private-body-copy", "actual-header-value@example.test").forEach {
            assertFalse(sanitized.toString().contains(it))
            assertFalse(safePayload.contains(it))
            assertFalse(safeBody.contains(it))
        }
    }

    @Test
    fun malformedBase64FailsClosedWithoutDiscardingTimingMetadata() {
        val record = captureRecord("").put("payload_base64", "private%%%invalid")
            .put("recognized_sip", true).put("unix_ms", 1789526880123L)
            .put("body_base64", encode("private-body"))
        val sanitized = sanitizer.sanitizeRecord(record)
        assertEquals(1789526880123L, sanitized.getLong("unix_ms"))
        assertFalse(sanitized.getBoolean("recognized_sip"))
        assertTrue(sanitized.isNull("payload_base64"))
        assertTrue(sanitized.isNull("body_base64"))
        assertFalse(sanitized.toString().contains("private"))
    }

    @Test
    fun malformedJsonFailureNeverEchoesPrivateSourceInErrorMessage() {
        val source = temporaryFolder.newFile("private-malformed.jsonl")
        val destination = temporaryFolder.newFile("private-malformed.sanitized.jsonl")
        source.writeText("{\"private-identifier\"")

        val result = sanitizer.sanitizeJsonLines(source, destination)

        assertEquals(1L, result.recordsDropped)
        assertFalse(destination.readText().contains("private-identifier"))
        assertEquals("invalid_capture_record", JSONObject(destination.readText()).getString("sanitizationError"))
    }

    @Test
    fun exportCountsMalformedLinesAndReportedNativeDropsWithoutStoppingOtherRecords() {
        val source = temporaryFolder.newFile("capture.jsonl")
        val destination = temporaryFolder.newFile("sanitized.jsonl")
        source.writeText(
            listOf(
                """{"record_type":"runtime_event","event":"capture_summary","dropped_event_count":3}""",
                "{truncated",
                """{"record_type":"runtime_event","event":"capture_summary","dropped_event_count":2}""",
                """{"event":"ordinary","dropped_event_count":100}"""
            ).joinToString("\n", postfix = "\n")
        )

        val result = sanitizer.sanitizeJsonLines(source, destination)

        assertEquals(3L, result.recordsSanitized)
        assertEquals(1L, result.recordsDropped)
        assertEquals(5L, result.reportedNativeDroppedEvents)
        val records = destination.readLines().map(::JSONObject)
        assertEquals(4, records.size)
        assertTrue(records[1].getBoolean("rawLineDropped"))
        assertEquals("ordinary", records.last().getString("event"))
    }

    @Test
    fun emptyCaptureHasNoUsableRecords() {
        val result = sanitizer.sanitizeJsonLines(
            temporaryFolder.newFile("empty.jsonl"),
            temporaryFolder.newFile("empty.sanitized.jsonl")
        )

        assertEquals(0L, result.recordsSanitized)
        assertEquals(0L, result.recordsDropped)
        assertEquals(0L, result.reportedNativeDroppedEvents)
    }

    private fun captureRecord(payload: String): JSONObject = JSONObject()
        .put("record_type", "transport_payload")
        .put("transport", "tcp")
        .put("direction", "incoming")
        .put("capture_point", "decoded_transport")
        .put("payload_base64", encode(payload))

    private fun encode(value: String): String = Base64.getEncoder()
        .encodeToString(value.toByteArray(StandardCharsets.ISO_8859_1))

    private fun decode(record: JSONObject, field: String): String = String(
        Base64.getDecoder().decode(record.getString(field)), StandardCharsets.ISO_8859_1
    )

    private fun headerValue(record: JSONObject, name: String): String? {
        val headers = record.getJSONArray("headers")
        return (0 until headers.length()).map(headers::getJSONObject)
            .firstOrNull { it.getString("name").equals(name, true) }?.getString("value")
    }
}
