package com.example.cellidtracker.experiment

import com.example.cellidtracker.data.ExperimentSampleEntity
import com.example.cellidtracker.data.ExperimentSessionEntity
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

        assertEquals(2, payload.schemaVersion)
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
