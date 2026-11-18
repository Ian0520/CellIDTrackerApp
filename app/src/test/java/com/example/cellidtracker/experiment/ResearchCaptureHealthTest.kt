package com.example.cellidtracker.experiment

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchCaptureHealthTest {
    private val healthy = ResearchCaptureHealth(
        nativeCaptureFilesCopied = 1,
        nativeCaptureRecords = 10,
        nativeCaptureCopyErrors = 0,
        callerContextWriteFailures = 0,
        sanitizationFailures = 0,
        nativeReportedDroppedEvents = 0
    )

    @Test
    fun enabledCaptureWithoutNativeFilesWarnsInsteadOfReportingHealthyExport() {
        val health = healthy.copy(nativeCaptureFilesCopied = 0, nativeCaptureRecords = 0)

        assertEquals(listOf("native_files_missing"), health.warnings.map { it.code })
        val message = buildSessionExportMessage(File("session.json"), researchExport(health))
        assertTrue(message.startsWith("Session JSON exported:"))
        assertTrue(message.contains("Research capture warning:"))
        assertTrue(message.contains("No native capture files were copied."))
        assertTrue(message.contains("manifest.json"))
    }

    @Test
    fun copiedEmptyFilesAndEachObservedFailureRemainVisible() {
        val health = healthy.copy(
            nativeCaptureRecords = 0,
            nativeCaptureCopyErrors = 2,
            callerContextWriteFailures = 3,
            sanitizationFailures = 4,
            nativeReportedDroppedEvents = 5
        )

        assertEquals(
            listOf("native_records_missing", "native_copy_errors", "context_write_failures",
                "sanitization_failures", "native_dropped_events"),
            health.warnings.map { it.code }
        )
    }

    @Test
    fun disabledCaptureDoesNotGenerateMissingNativeFileWarning() {
        val message = buildSessionExportMessage(File("session.json"), researchExport = null)

        assertTrue(message.startsWith("Session exported:"))
        assertFalse(message.contains("Research"))
        assertFalse(message.contains("warning", ignoreCase = true))
    }

    @Test
    fun researchFailureKeepsSessionJsonSuccessDistinct() {
        val message = buildSessionExportMessage(
            File("session.json"),
            researchExport = null,
            researchExportFailure = IOException("No export storage")
        )

        assertTrue(message.startsWith("Session JSON exported:"))
        assertTrue(message.contains("Research export failed: No export storage"))
    }

    @Test
    fun noObservedFailuresDoesNotClaimCaptureCompleteness() {
        assertTrue(healthy.warnings.isEmpty())
        val message = buildSessionExportMessage(File("session.json"), researchExport(healthy))

        assertTrue(message.contains("Research export:"))
        assertFalse(message.contains("complete", ignoreCase = true))
        assertFalse(message.contains("warning", ignoreCase = true))
    }

    @Test
    fun messageLossAndNativePacketDiagnosticsWarnEvenWhenNoRecorderDropsWereReported() {
        val health = healthy.copy(
            incompleteSipStreams = 1,
            incompleteSipDatagrams = 2,
            malformedSipMessages = 3,
            sipStreamConflicts = 1,
            sipStreamGaps = 1,
            nativeSequenceDiscontinuities = 1,
            sipReassemblyLimitExceeded = 1,
            nativePacketQualityIssues = 1
        )

        assertEquals(0L, health.nativeReportedDroppedEvents)
        assertEquals(
            listOf("sip_streams_incomplete", "sip_datagrams_incomplete", "sip_messages_malformed",
                "sip_stream_integrity", "sip_reassembly_limit", "native_packet_incomplete"),
            health.warnings.map { it.code }
        )
        assertTrue(buildSessionExportMessage(File("session.json"), researchExport(health))
            .contains("Research capture warning:"))
    }

    @Test
    fun retryAfterSessionJsonFailureRetainsOriginalCaptureWarning() {
        val state = ResearchExportRetryState()
        var captureCalls = 0
        val first = state.getOrCapture(7) {
            captureCalls++
            researchExport(healthy.copy(nativeCaptureCopyErrors = 1))
        }
        // The session JSON export can fail after native capture finalization. Its retry
        // must reuse the capture outcome rather than call finish() on a closed capture.
        val retry = state.getOrCapture(7) { captureCalls++; null }

        assertEquals(1, captureCalls)
        assertEquals(first, retry)
        assertTrue(state.isPending(7))
        assertTrue(buildSessionExportMessage(File("session.json"), retry.getOrNull())
            .contains("1 native capture file copy error(s)."))
        state.clear(7)
        assertFalse(state.isPending(7))
    }

    @Test
    fun retryRetainsResearchFailureAndDoesNotClaimSuccess() {
        val state = ResearchExportRetryState()
        var captureCalls = 0
        state.getOrCapture(7) { captureCalls++; throw IOException("Capture storage unavailable") }
        val retry = state.getOrCapture(7) { captureCalls++; null }

        assertEquals(1, captureCalls)
        assertTrue(buildSessionExportMessage(File("session.json"), retry.getOrNull(), retry.exceptionOrNull())
            .contains("Research export failed: Capture storage unavailable"))
    }

    @Test
    fun disabledCaptureResultIsRetainedAndAnotherSessionDoesNotReuseIt() {
        val state = ResearchExportRetryState()
        var captureCalls = 0
        state.getOrCapture(7) { captureCalls++; null }
        state.getOrCapture(7) { captureCalls++; researchExport(healthy) }
        val anotherSession = state.getOrCapture(8) { captureCalls++; researchExport(healthy) }

        assertEquals(2, captureCalls)
        assertEquals(healthy, anotherSession.getOrNull()?.health)
        assertFalse(state.isPending(7))
        assertTrue(state.isPending(8))
    }

    @Test
    fun cancellationPropagatesAndIsNotCachedAsResearchFailure() {
        val state = ResearchExportRetryState()
        var cancelled = false
        try {
            state.getOrCapture(7) { throw CancellationException("Cancelled") }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(state.isPending(7))
        val retry = state.getOrCapture(7) { researchExport(healthy) }
        assertTrue(retry.isSuccess)
    }

    private fun researchExport(health: ResearchCaptureHealth) = ResearchExportResult(
        exportDirectory = File("research"),
        rawDirectory = File("raw"),
        copiedCaptureFiles = health.nativeCaptureFilesCopied,
        health = health
    )
}
