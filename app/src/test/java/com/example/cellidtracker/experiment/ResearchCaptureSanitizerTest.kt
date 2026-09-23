package com.example.cellidtracker.experiment

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchCaptureSanitizerTest {
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
        val sip = """
            SIP/2.0 183 Session Progress
            Via: SIP/2.0/TCP 192.0.2.10;branch=z9hG4bK-secret
            Authorization: Digest username="+886900000000", response="credential"
             continued-secret
            Call-ID: call-secret@example.test
            From: <sip:+886900000000@example.test>;tag=from-secret
            To: <sip:+886911111111@example.test>;tag=to-secret
            Content-Type: application/sdp

            v=0
            c=IN IP6 2001:db8::20
        """.trimIndent().replace("\n", "\r\n")
        val body = sip.substringAfter("\r\n\r\n")
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

        assertEquals("<redacted>", sanitized.getJSONArray("headers").getJSONObject(0).getString("value"))
        assertEquals(2, sanitized.getJSONArray("headers").length())
        assertTrue(sanitizedSip.contains("Authorization: <redacted>"))
        assertTrue(sanitizedSip.contains(" <redacted>"))
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
        assertTrue(sanitized.getBoolean("sanitized"))
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
}
