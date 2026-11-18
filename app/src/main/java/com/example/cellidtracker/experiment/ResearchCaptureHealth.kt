package com.example.cellidtracker.experiment

import java.io.File
import java.util.concurrent.CancellationException

internal data class CaptureExportWarning(val code: String, val message: String)

/** Keep capture finalization results while the ordinary session JSON export is retried. */
internal class ResearchExportRetryState {
    private var pendingSessionDbId: Long? = null
    private var pendingOutcome: Result<ResearchExportResult?>? = null

    @Synchronized
    fun getOrCapture(
        sessionDbId: Long,
        capture: () -> ResearchExportResult?
    ): Result<ResearchExportResult?> {
        if (pendingSessionDbId == sessionDbId) {
            pendingOutcome?.let { return it }
        }
        val outcome = try {
            Result.success(capture())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
        pendingSessionDbId = sessionDbId
        pendingOutcome = outcome
        return outcome
    }

    @Synchronized
    fun isPending(sessionDbId: Long): Boolean = pendingSessionDbId == sessionDbId

    @Synchronized
    fun clear(sessionDbId: Long) {
        if (pendingSessionDbId == sessionDbId) {
            pendingSessionDbId = null
            pendingOutcome = null
        }
    }
}

/** Observed export problems; an empty warning list does not prove capture completeness. */
internal data class ResearchCaptureHealth(
    val nativeCaptureFilesCopied: Int,
    val nativeCaptureRecords: Long,
    val nativeCaptureCopyErrors: Int,
    val callerContextWriteFailures: Long,
    val sanitizationFailures: Long,
    val nativeReportedDroppedEvents: Long,
    val incompleteSipStreams: Long = 0,
    val incompleteSipDatagrams: Long = 0,
    val malformedSipMessages: Long = 0,
    val sipStreamConflicts: Long = 0,
    val sipStreamGaps: Long = 0,
    val nativeSequenceDiscontinuities: Long = 0,
    val sipReassemblyLimitExceeded: Long = 0,
    val nativePacketQualityIssues: Long = 0
) {
    val warnings: List<CaptureExportWarning>
        get() = buildList {
            if (nativeCaptureFilesCopied == 0) {
                add(CaptureExportWarning("native_files_missing", "No native capture files were copied."))
            } else if (nativeCaptureRecords == 0L) {
                add(CaptureExportWarning("native_records_missing", "Native capture files contain no usable JSON records."))
            }
            if (nativeCaptureCopyErrors > 0) {
                add(CaptureExportWarning("native_copy_errors", "$nativeCaptureCopyErrors native capture file copy error(s)."))
            }
            if (callerContextWriteFailures > 0) {
                add(CaptureExportWarning("context_write_failures", "$callerContextWriteFailures caller context write failure(s)."))
            }
            if (sanitizationFailures > 0) {
                add(CaptureExportWarning("sanitization_failures", "$sanitizationFailures JSON line(s) could not be sanitized and were replaced with error records."))
            }
            if (nativeReportedDroppedEvents > 0) {
                add(CaptureExportWarning("native_dropped_events", "Native capture summaries reported $nativeReportedDroppedEvents dropped event(s)."))
            }
            if (incompleteSipStreams > 0) {
                add(CaptureExportWarning("sip_streams_incomplete", "$incompleteSipStreams SIP stream(s) contain incomplete message data."))
            }
            if (incompleteSipDatagrams > 0) {
                add(CaptureExportWarning("sip_datagrams_incomplete", "$incompleteSipDatagrams datagram(s) could not be exported as complete SIP messages."))
            }
            if (malformedSipMessages > 0) {
                add(CaptureExportWarning("sip_messages_malformed", "$malformedSipMessages malformed SIP message(s) were excluded from message export."))
            }
            if (sipStreamConflicts > 0 || sipStreamGaps > 0 || nativeSequenceDiscontinuities > 0) {
                add(CaptureExportWarning("sip_stream_integrity", "SIP reconstruction found $sipStreamConflicts conflicting stream(s), $sipStreamGaps gap(s), and $nativeSequenceDiscontinuities capture sequence discontinuity/discontinuities."))
            }
            if (sipReassemblyLimitExceeded > 0) {
                add(CaptureExportWarning("sip_reassembly_limit", "SIP reconstruction reached a size or stream limit $sipReassemblyLimitExceeded time(s)."))
            }
            if (nativePacketQualityIssues > 0) {
                add(CaptureExportWarning("native_packet_incomplete", "Native capture reported $nativePacketQualityIssues fragmented, truncated or invalid-length packet(s)."))
            }
        }
}

internal fun buildSessionExportMessage(
    sessionFile: File,
    researchExport: ResearchExportResult?,
    researchExportFailure: Throwable? = null
): String = buildString {
    if (researchExportFailure != null) {
        append("Session JSON exported: ${sessionFile.absolutePath}. Research export failed: ")
        append(researchExportFailure.message ?: researchExportFailure.javaClass.simpleName)
    } else if (researchExport != null && researchExport.health.warnings.isNotEmpty()) {
        append("Session JSON exported: ${sessionFile.absolutePath}. Research capture warning: ")
        append(researchExport.health.warnings.joinToString(" ") { it.message })
        append(" Research export: ${researchExport.exportDirectory.absolutePath}. See manifest.json.")
    } else {
        append("Session exported: ${sessionFile.absolutePath}")
        researchExport?.let { append(" Research export: ${it.exportDirectory.absolutePath}") }
    }
}
