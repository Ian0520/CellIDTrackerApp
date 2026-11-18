package com.example.cellidtracker.experiment

import java.io.File
import org.json.JSONObject

internal data class NativeCaptureExportResult(
    val packetRecords: Long,
    val sanitizationFailures: Long,
    val reportedNativeDroppedEvents: Long,
    val packetQualityIssues: Long,
    val reassembly: SipReassemblySummary,
    val files: List<File>
)

/**
 * Reconstruct messages from private bytes before privacy filtering removes
 * transport continuations. The source files are never modified.
 */
internal fun exportNativeCapture(
    sourceDirectory: File,
    destinationDirectory: File,
    sanitizer: CaptureSanitizer
): NativeCaptureExportResult {
    require(sourceDirectory.canonicalFile != destinationDirectory.canonicalFile) {
        "Native export requires a separate destination"
    }
    destinationDirectory.mkdirs()
    // A retry must not leave an older, less-redacted file outside the new manifest.
    val previousExport = Regex("^(?:sip_capture\\.sanitized\\.jsonl(?:\\.[1-9][0-9]*)?|sip_messages\\.sanitized\\.jsonl)$")
    destinationDirectory.listFiles().orEmpty().filter { previousExport.matches(it.name) }.forEach {
        check(it.isFile && it.delete()) { "Unable to replace previous native export" }
    }
    val captureName = Regex("^sip_capture\\.jsonl(?:\\.([1-9][0-9]*))?$")
    val sources = sourceDirectory.listFiles().orEmpty()
        .filter { it.isFile && captureName.matches(it.name) }
        // Native rotation moves older records to progressively higher suffixes.
        .sortedByDescending { captureName.matchEntire(it.name)!!.groupValues[1].toIntOrNull() ?: 0 }
    var records = 0L
    var failures = 0L
    var dropped = 0L
    var qualityIssues = 0L
    val files = mutableListOf<File>()
    val messages = File(destinationDirectory, "sip_messages.sanitized.jsonl")
    var summary: SipReassemblySummary? = null
    messages.bufferedWriter().use { messageWriter ->
        val reassembler = SipMessageReassembler { message ->
            val sanitized = sanitizer.sanitizeRecord(message)
            check(sanitized.optBoolean("recognized_sip") && sanitized.optBoolean("complete")) {
                "Reconstructed SIP message failed export validation"
            }
            messageWriter.append(sanitized.toString()).append('\n')
        }
        sources.forEach { source ->
            val destination = File(
                destinationDirectory,
                source.name.replaceFirst(".jsonl", ".sanitized.jsonl")
            )
            destination.bufferedWriter().use { packetWriter ->
                source.useLines { lines ->
                    lines.forEach lineLoop@ { line ->
                        val record = try {
                            JSONObject(line)
                        } catch (_: Exception) {
                            failures++
                            packetWriter.append(safeNativeError().toString()).append('\n')
                            return@lineLoop
                        }
                        records++
                        if (record.optString("record_type") == "runtime_event") {
                            if (record.optString("event") == "capture_summary") {
                                dropped += record.optLong("dropped_event_count", 0L).coerceAtLeast(0L)
                            }
                            if (record.optString("event") in PACKET_QUALITY_EVENTS) qualityIssues++
                        }
                        // The sanitizer mutates its input. Never mutate held stream records.
                        reassembler.accept(record)
                        val sanitized = try {
                            sanitizer.sanitizeRecord(JSONObject(record.toString()))
                        } catch (_: Exception) {
                            failures++
                            safeNativeError()
                        }
                        packetWriter.append(sanitized.toString()).append('\n')
                    }
                }
            }
            files += destination
        }
        summary = reassembler.finish()
    }
    files += messages
    return NativeCaptureExportResult(
        packetRecords = records,
        sanitizationFailures = failures,
        reportedNativeDroppedEvents = dropped,
        packetQualityIssues = qualityIssues,
        reassembly = requireNotNull(summary),
        files = files
    )
}

private fun safeNativeError() = JSONObject()
    .put("sanitizationError", "invalid_native_record")
    .put("rawLineDropped", true)

private val PACKET_QUALITY_EVENTS = setOf(
    "packet_incomplete", "packet_fragment", "packet_invalid_length", "packet_receive_truncated",
    "packet_unsupported_extension"
)
