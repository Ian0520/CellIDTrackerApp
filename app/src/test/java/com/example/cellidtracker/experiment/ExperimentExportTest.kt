package com.example.cellidtracker.experiment

import com.example.cellidtracker.data.ExperimentSampleEntity
import com.example.cellidtracker.data.ExperimentSessionEntity
import com.example.cellidtracker.data.ProbeAttemptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExperimentExportTest {
    @Test
    fun exportIncludesRequiredProbeSessionFields() {
        val payload = buildExperimentSessionExportPayload(
            session = sampleSession(),
            samples = listOf(
                ExperimentSampleEntity(
                    id = 1,
                    sessionDbId = 11,
                    recordedAtMillis = 2000,
                    victim = "victim-a",
                    mcc = 466,
                    mnc = 92,
                    lac = 13700,
                    cid = 81261592,
                    estimatedLat = 25.033,
                    estimatedLon = 121.565,
                    estimatedAccuracyM = 900.0,
                    geolocationStatus = "success",
                    geolocationError = null,
                    towersCount = 2,
                    towersJson = "[{\"cid\":1}]",
                    moving = false,
                    deltaMs = 700,
                    sampleType = "cell",
                    sipStatus = 183,
                    inviteMs = 100,
                    prMs = 800,
                    intercarrierCandidate = false,
                    probeId = "call-1",
                    inviteSentAtMillis = 1300,
                    responseReceivedAtMillis = 2000,
                    outcome = "success",
                    intervalSincePreviousProbeMs = 30_000,
                    wifiRssiDbm = -61,
                    wifiFrequencyMhz = 5180,
                    wifiLinkSpeedMbps = 433,
                    wifiBssidHash = "bssid-hash",
                    createdAtMillis = 2000
                )
            ),
            exportedAtMillis = 3000,
            appInfo = ProbeExportAppInfo(
                appName = "CellIDTracker",
                appPackage = "com.example.cellidtracker",
                appVersionName = "1.0",
                deviceIdentifier = "vendor_model_device"
            )
        )

        assertEquals(4, payload.schemaVersion)
        assertEquals("probe", payload.appType)
        assertEquals("session-11", payload.sessionId)
        assertEquals(1000L, payload.startedAtMillis)
        assertEquals(2500L, payload.endedAtMillis)
        assertEquals("CellIDTracker", payload.appName)
        assertEquals("com.example.cellidtracker", payload.appPackage)
        assertEquals("1.0", payload.appVersionName)
        assertEquals("vendor_model_device", payload.deviceIdentifier)

        val sample = payload.samples.first()
        assertEquals(2000L, sample.recordedAtMillis)
        assertEquals("victim-a", sample.victim)
        assertEquals(466, sample.mcc)
        assertEquals(92, sample.mnc)
        assertEquals(13700, sample.lac)
        assertEquals(81261592, sample.cid)
        assertEquals("success", sample.geolocationStatus)
        assertNull(sample.geolocationError)
        assertEquals(2, sample.towersCount)
        assertEquals("[{\"cid\":1}]", sample.towersJson)
        assertEquals(700L, sample.deltaMs)
        assertEquals("cell", sample.sampleType)
        assertEquals(183, sample.sipStatus)
        assertEquals(100L, sample.inviteMs)
        assertEquals(800L, sample.prMs)
        assertEquals(false, sample.intercarrierCandidate)
        assertEquals("call-1", sample.probeId)
        assertEquals(1300L, sample.inviteSentAtMillis)
        assertEquals(2000L, sample.responseReceivedAtMillis)
        assertEquals("success", sample.outcome)
        assertEquals(30_000L, sample.intervalSincePreviousProbeMs)
        assertEquals(-61, sample.wifiRssiDbm)
        assertEquals(5180, sample.wifiFrequencyMhz)
        assertEquals(433, sample.wifiLinkSpeedMbps)
        assertEquals("bssid-hash", sample.wifiBssidHash)
        assertNull(sample.contractVersion)
        assertNull(sample.finishReason)
    }

    @Test
    fun structuredAttemptExportsAsOneCompleteSample() {
        val payload = buildExperimentSessionExportPayload(
            session = sampleSession(),
            samples = emptyList(),
            attempts = listOf(
                ProbeAttemptEntity(
                    attemptKey = "11:7:call-structured",
                    attemptId = "call-structured",
                    contractVersion = 1,
                    sessionDbId = 11,
                    probeRunId = 7,
                    victim = "victim-a",
                    moving = true,
                    inviteElapsedMs = 100,
                    inviteSentAtMillis = 1_300,
                    responseElapsedMs = 800,
                    responseReceivedAtMillis = 2_000,
                    sipStatus = 183,
                    deltaMs = 700,
                    mcc = 466,
                    mnc = 92,
                    lac = 13_700,
                    cid = 81_261_592,
                    estimatedLat = 25.033,
                    estimatedLon = 121.565,
                    estimatedAccuracyM = 900.0,
                    geolocationStatus = "success",
                    geolocationError = null,
                    towersCount = 1,
                    towersJson = "[{\"cid\":81261592}]",
                    intercarrierCandidate = false,
                    intervalSincePreviousProbeMs = 30_000,
                    wifiRssiDbm = -61,
                    wifiFrequencyMhz = 5_180,
                    wifiLinkSpeedMbps = 433,
                    wifiBssidHash = "bssid-hash",
                    finishedAtMillis = 2_500,
                    outcome = "cell_observed",
                    finishReason = "next_invite",
                    createdAtMillis = 1_300,
                    updatedAtMillis = 2_500
                )
            ),
            exportedAtMillis = 3_000,
            appInfo = ProbeExportAppInfo(
                appName = "CellIDTracker",
                appPackage = "com.example.cellidtracker",
                appVersionName = "1.0",
                deviceIdentifier = null
            )
        )

        assertEquals(1, payload.samples.size)
        val sample = payload.samples.single()
        assertEquals("call-structured", sample.probeId)
        assertEquals("cell", sample.sampleType)
        assertEquals(1, sample.contractVersion)
        assertEquals(700L, sample.deltaMs)
        assertEquals(2_500L, sample.finishedAtMillis)
        assertEquals("next_invite", sample.finishReason)
    }

    @Test
    fun samplesAreSortedByRecordedAtMillisAscending() {
        val payload = buildExperimentSessionExportPayload(
            session = sampleSession(),
            samples = listOf(
                ExperimentSampleEntity(
                    id = 2,
                    sessionDbId = 11,
                    recordedAtMillis = 5000,
                    victim = "v",
                    mcc = 1,
                    mnc = 1,
                    lac = 1,
                    cid = 1,
                    estimatedLat = null,
                    estimatedLon = null,
                    estimatedAccuracyM = null,
                    geolocationStatus = "failure",
                    geolocationError = "err",
                    towersCount = 1,
                    towersJson = "[]",
                    moving = false,
                    deltaMs = null,
                    sampleType = "cell",
                    sipStatus = null,
                    inviteMs = null,
                    prMs = null,
                    intercarrierCandidate = null,
                    createdAtMillis = 5000
                ),
                ExperimentSampleEntity(
                    id = 1,
                    sessionDbId = 11,
                    recordedAtMillis = 1000,
                    victim = "v",
                    mcc = 1,
                    mnc = 1,
                    lac = 1,
                    cid = 1,
                    estimatedLat = null,
                    estimatedLon = null,
                    estimatedAccuracyM = null,
                    geolocationStatus = "failure",
                    geolocationError = "err",
                    towersCount = 1,
                    towersJson = "[]",
                    moving = false,
                    deltaMs = null,
                    sampleType = "cell",
                    sipStatus = null,
                    inviteMs = null,
                    prMs = null,
                    intercarrierCandidate = null,
                    createdAtMillis = 1000
                )
            ),
            exportedAtMillis = 3000,
            appInfo = ProbeExportAppInfo(
                appName = "CellIDTracker",
                appPackage = "com.example.cellidtracker",
                appVersionName = "1.0",
                deviceIdentifier = null
            )
        )

        assertEquals(1000L, payload.samples[0].recordedAtMillis)
        assertEquals(5000L, payload.samples[1].recordedAtMillis)
    }

    private fun sampleSession(): ExperimentSessionEntity {
        return ExperimentSessionEntity(
            id = 11,
            sessionId = "session-11",
            startedAtMillis = 1000,
            endedAtMillis = 2500,
            createdAtMillis = 1000,
            exportedAtMillis = null
        )
    }
}
