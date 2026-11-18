package com.example.cellidtracker.experiment

import java.nio.charset.StandardCharsets

/** A SIP frame with boundaries independently validated against its bytes. */
internal data class ParsedSipMessage(
    val startLine: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val payload: ByteArray,
    val method: String,
    val status: Int,
    val reason: String
)

internal object SipMessageFraming {
    internal const val MAX_HEADER_BYTES = 64 * 1024
    internal const val MAX_MESSAGE_BYTES = 1024 * 1024
    private val token = Regex("[A-Za-z0-9.!%*_+`'~\\-]+")
    private val request = Regex("([A-Za-z0-9.!%*_+`'~\\-]+) (\\*|[A-Za-z][A-Za-z0-9+.\\-]*:[^\\x00-\\x20\\x7f]+) SIP/2\\.0")
    private val response = Regex("SIP/2\\.0 ([1-6][0-9]{2}) ([^\\r\\n]*)")

    internal sealed class Inspection {
        data class Complete(val message: ParsedSipMessage) : Inspection()
        data object Incomplete : Inspection()
        data object Malformed : Inspection()
        data object MissingLength : Inspection()
        data object TooLarge : Inspection()
    }

    /** No inferred bytes, trailing bytes, or incomplete bodies are accepted. */
    fun parseComplete(payload: ByteArray, requireContentLength: Boolean = true): ParsedSipMessage? {
        val result = inspect(payload, requireContentLength)
        return (result as? Inspection.Complete)?.message?.takeIf { it.payload.size == payload.size }
    }

    /** Reads one frame from the beginning of a contiguous byte range. */
    internal fun inspect(bytes: ByteArray, requireContentLength: Boolean): Inspection {
        var boundary = -1
        var delimiterSize = 0
        for (index in 0 until minOf(bytes.size - 1, MAX_HEADER_BYTES)) {
            if (index + 3 < bytes.size && bytes[index] == 13.toByte() &&
                bytes[index + 1] == 10.toByte() && bytes[index + 2] == 13.toByte() &&
                bytes[index + 3] == 10.toByte()) {
                boundary = index
                delimiterSize = 4
                break
            }
            if (bytes[index] == 10.toByte() && bytes[index + 1] == 10.toByte()) {
                boundary = index
                delimiterSize = 2
                break
            }
        }
        if (boundary < 0) return if (bytes.size >= MAX_HEADER_BYTES) Inspection.TooLarge else Inspection.Incomplete
        val headerText = String(bytes, 0, boundary, StandardCharsets.ISO_8859_1)
        // A lone CR is not a line delimiter; control bytes cannot turn body fragments into headers.
        val lines = headerText.split('\n').map { it.removeSuffix("\r") }
        if (lines.any { line -> line.any { it == '\r' || (it.code < 32 && it != '\t') || it.code == 127 } }) {
            return Inspection.Malformed
        }
        val startLine = lines.firstOrNull() ?: return Inspection.Malformed
        val requestMatch = request.matchEntire(startLine)
        val responseMatch = response.matchEntire(startLine)
        if (requestMatch == null && responseMatch == null) return Inspection.Malformed
        val headers = mutableListOf<Pair<String, String>>()
        for (line in lines.drop(1)) {
            if (line.startsWith(' ') || line.startsWith('\t')) {
                if (headers.isEmpty()) return Inspection.Malformed
                val previous = headers.removeAt(headers.lastIndex)
                headers += previous.first to (previous.second + " " + line.trim())
            } else {
                val colon = line.indexOf(':')
                if (colon <= 0) return Inspection.Malformed
                val name = line.substring(0, colon).trim()
                if (!token.matches(name)) return Inspection.Malformed
                headers += name to line.substring(colon + 1).trim()
            }
        }
        val lengths = headers.filter { it.first.equals("Content-Length", true) || it.first.equals("l", true) }
        if (lengths.size > 1) return Inspection.Malformed
        if (lengths.isEmpty() && requireContentLength) return Inspection.MissingLength
        val bodyStart = boundary + delimiterSize
        val bodyLength = if (lengths.isEmpty()) bytes.size - bodyStart else {
            val value = lengths.single().second
            if (value.isEmpty() || value.any { it !in '0'..'9' }) return Inspection.Malformed
            val parsed = value.toLongOrNull() ?: return Inspection.TooLarge
            if (parsed > MAX_MESSAGE_BYTES) return Inspection.TooLarge
            parsed.toInt()
        }
        val messageSize = bodyStart.toLong() + bodyLength
        if (messageSize > MAX_MESSAGE_BYTES) return Inspection.TooLarge
        if (messageSize > bytes.size) return Inspection.Incomplete
        val end = messageSize.toInt()
        return Inspection.Complete(ParsedSipMessage(
            startLine = startLine,
            headers = headers,
            body = bytes.copyOfRange(bodyStart, end),
            payload = bytes.copyOfRange(0, end),
            method = requestMatch?.groupValues?.get(1).orEmpty(),
            status = responseMatch?.groupValues?.get(1)?.toInt() ?: 0,
            reason = responseMatch?.groupValues?.get(2).orEmpty()
        ))
    }
}
