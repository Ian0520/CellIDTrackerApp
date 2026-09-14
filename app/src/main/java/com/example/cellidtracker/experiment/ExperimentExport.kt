package com.example.cellidtracker.experiment

import android.content.Context
import android.os.Build
import com.example.cellidtracker.BuildConfig
import com.example.cellidtracker.data.ExperimentSampleEntity
import com.example.cellidtracker.data.ExperimentSessionEntity
import com.example.cellidtracker.data.HistoryDatabase
import com.example.cellidtracker.data.ProbeAttemptEntity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val PROBE_SESSION_EXPORT_SCHEMA_VERSION = 4
private const val PROBE_SESSION_EXPORT_APP_TYPE = "probe"

data class ProbeExportAppInfo(
    val appName: String,
    val appPackage: String,
    val appVersionName: String,
    val deviceIdentifier: String?
)

data class ProbeSessionSampleExportPayload(
    val recordedAtMillis: Long,
    val victim: String,
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,
    val estimatedLat: Double?,
    val estimatedLon: Double?,
    val estimatedAccuracyM: Double?,
    val geolocationStatus: String,
    val geolocationError: String?,
    val towersCount: Int,
    val towersJson: String,
    val deltaMs: Long?,
    val sampleType: String,
    val sipStatus: Int?,
    val inviteMs: Long?,
    val prMs: Long?,
    val intercarrierCandidate: Boolean?,
    val probeId: String?,
    val inviteSentAtMillis: Long?,
    val responseReceivedAtMillis: Long?,
    val outcome: String?,
    val intervalSincePreviousProbeMs: Long?,
    val wifiRssiDbm: Int?,
    val wifiFrequencyMhz: Int?,
    val wifiLinkSpeedMbps: Int?,
    val wifiBssidHash: String?,
    val moving: Boolean,
    val contractVersion: Int?,
    val finishedAtMillis: Long?,
    val finishReason: String?
)

data class ProbeSessionExportPayload(
    val schemaVersion: Int,
    val appType: String,
    val appName: String,
    val appPackage: String,
    val appVersionName: String,
    val deviceIdentifier: String?,
    val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val exportedAtMillis: Long,
    val samples: List<ProbeSessionSampleExportPayload>
)

fun buildExperimentSessionExportPayload(
    session: ExperimentSessionEntity,
    samples: List<ExperimentSampleEntity>,
    attempts: List<ProbeAttemptEntity> = emptyList(),
    exportedAtMillis: Long,
    appInfo: ProbeExportAppInfo
): ProbeSessionExportPayload {
    val legacyPayloads = samples.map { it.toExportPayload() }
    val attemptPayloads = attempts.map { it.toExportPayload() }
    val sortedSamples = (legacyPayloads + attemptPayloads).sortedWith(
        compareBy<ProbeSessionSampleExportPayload> { it.recordedAtMillis }
            .thenBy { it.probeId.orEmpty() }
    )

    return ProbeSessionExportPayload(
        schemaVersion = PROBE_SESSION_EXPORT_SCHEMA_VERSION,
        appType = PROBE_SESSION_EXPORT_APP_TYPE,
        appName = appInfo.appName,
        appPackage = appInfo.appPackage,
        appVersionName = appInfo.appVersionName,
        deviceIdentifier = appInfo.deviceIdentifier,
        sessionId = session.sessionId,
        startedAtMillis = session.startedAtMillis,
        endedAtMillis = session.endedAtMillis,
        exportedAtMillis = exportedAtMillis,
        samples = sortedSamples
    )
}

fun buildExperimentSessionExportJson(
    session: ExperimentSessionEntity,
    samples: List<ExperimentSampleEntity>,
    attempts: List<ProbeAttemptEntity> = emptyList(),
    exportedAtMillis: Long,
    appInfo: ProbeExportAppInfo
): JSONObject {
    val payload = buildExperimentSessionExportPayload(
        session = session,
        samples = samples,
        attempts = attempts,
        exportedAtMillis = exportedAtMillis,
        appInfo = appInfo
    )
    return payloadToJson(payload)
}

suspend fun exportExperimentSessionToFile(
    ctx: Context,
    db: HistoryDatabase,
    sessionDbId: Long
): File = withContext(Dispatchers.IO) {
    val dao = db.experimentDao()
    val session = dao.getSessionById(sessionDbId)
        ?: throw IOException("Experiment session $sessionDbId not found")
    if (session.endedAtMillis == null) {
        throw IOException("Session ${session.sessionId} is still active")
    }

    val samples = dao.getSamplesForSession(sessionDbId)
    val attempts = db.probeAttemptDao().getForSession(sessionDbId)
    val exportedAtMillis = System.currentTimeMillis()
    val appInfo = ProbeExportAppInfo(
        appName = ctx.applicationInfo.loadLabel(ctx.packageManager).toString(),
        appPackage = ctx.packageName,
        appVersionName = BuildConfig.VERSION_NAME,
        deviceIdentifier = buildDeviceIdentifier()
    )
    val payload = buildExperimentSessionExportPayload(
        session = session,
        samples = samples,
        attempts = attempts,
        exportedAtMillis = exportedAtMillis,
        appInfo = appInfo
    )

    val baseDir = ctx.getExternalFilesDir(null)
        ?: throw IOException("External files directory unavailable")
    val exportDir = File(baseDir, "experiment_sessions").apply { mkdirs() }
    val outFile = File(exportDir, "${session.sessionId}.json")
    outFile.writeText(payloadToJson(payload).toString(2))
    dao.updateSessionExportTimestamp(sessionDbId, exportedAtMillis)
    outFile
}

private fun payloadToJson(payload: ProbeSessionExportPayload): JSONObject {
    return JSONObject()
        .put("schemaVersion", payload.schemaVersion)
        .put("appType", payload.appType)
        .put("appName", payload.appName)
        .put("appPackage", payload.appPackage)
        .put("appVersionName", payload.appVersionName)
        .put("deviceIdentifier", jsonValue(payload.deviceIdentifier))
        .put("sessionId", payload.sessionId)
        .put("startedAtMillis", payload.startedAtMillis)
        .put("endedAtMillis", jsonValue(payload.endedAtMillis))
        .put("exportedAtMillis", payload.exportedAtMillis)
        .put("samples", JSONArray().apply {
            payload.samples.forEach { put(sampleToJson(it)) }
        })
}

private fun sampleToJson(sample: ProbeSessionSampleExportPayload): JSONObject {
    return JSONObject()
        .put("recordedAtMillis", sample.recordedAtMillis)
        .put("victim", sample.victim)
        .put("mcc", sample.mcc)
        .put("mnc", sample.mnc)
        .put("lac", sample.lac)
        .put("cid", sample.cid)
        .put("estimatedLat", jsonValue(sample.estimatedLat))
        .put("estimatedLon", jsonValue(sample.estimatedLon))
        .put("estimatedAccuracyM", jsonValue(sample.estimatedAccuracyM))
        .put("geolocationStatus", sample.geolocationStatus)
        .put("geolocationError", jsonValue(sample.geolocationError))
        .put("towersCount", sample.towersCount)
        .put("towersJson", sample.towersJson)
        .put("deltaMs", jsonValue(sample.deltaMs))
        .put("sampleType", sample.sampleType)
        .put("sipStatus", jsonValue(sample.sipStatus))
        .put("inviteMs", jsonValue(sample.inviteMs))
        .put("prMs", jsonValue(sample.prMs))
        .put("intercarrierCandidate", jsonValue(sample.intercarrierCandidate))
        .put("probeId", jsonValue(sample.probeId))
        .put("inviteSentAtMillis", jsonValue(sample.inviteSentAtMillis))
        .put("responseReceivedAtMillis", jsonValue(sample.responseReceivedAtMillis))
        .put("outcome", jsonValue(sample.outcome))
        .put("intervalSincePreviousProbeMs", jsonValue(sample.intervalSincePreviousProbeMs))
        .put("wifiRssiDbm", jsonValue(sample.wifiRssiDbm))
        .put("wifiFrequencyMhz", jsonValue(sample.wifiFrequencyMhz))
        .put("wifiLinkSpeedMbps", jsonValue(sample.wifiLinkSpeedMbps))
        .put("wifiBssidHash", jsonValue(sample.wifiBssidHash))
        .put("moving", sample.moving)
        .put("contractVersion", jsonValue(sample.contractVersion))
        .put("finishedAtMillis", jsonValue(sample.finishedAtMillis))
        .put("finishReason", jsonValue(sample.finishReason))
}

private fun ExperimentSampleEntity.toExportPayload(): ProbeSessionSampleExportPayload {
    return ProbeSessionSampleExportPayload(
        recordedAtMillis = recordedAtMillis,
        victim = victim,
        mcc = mcc,
        mnc = mnc,
        lac = lac,
        cid = cid,
        estimatedLat = estimatedLat,
        estimatedLon = estimatedLon,
        estimatedAccuracyM = estimatedAccuracyM,
        geolocationStatus = geolocationStatus,
        geolocationError = geolocationError,
        towersCount = towersCount,
        towersJson = towersJson,
        deltaMs = deltaMs,
        sampleType = sampleType,
        sipStatus = sipStatus,
        inviteMs = inviteMs,
        prMs = prMs,
        intercarrierCandidate = intercarrierCandidate,
        probeId = probeId,
        inviteSentAtMillis = inviteSentAtMillis,
        responseReceivedAtMillis = responseReceivedAtMillis,
        outcome = outcome,
        intervalSincePreviousProbeMs = intervalSincePreviousProbeMs,
        wifiRssiDbm = wifiRssiDbm,
        wifiFrequencyMhz = wifiFrequencyMhz,
        wifiLinkSpeedMbps = wifiLinkSpeedMbps,
        wifiBssidHash = wifiBssidHash,
        moving = moving,
        contractVersion = null,
        finishedAtMillis = null,
        finishReason = null
    )
}

private fun ProbeAttemptEntity.toExportPayload(): ProbeSessionSampleExportPayload {
    return ProbeSessionSampleExportPayload(
        recordedAtMillis = responseReceivedAtMillis ?: inviteSentAtMillis ?: createdAtMillis,
        victim = victim,
        mcc = mcc ?: -1,
        mnc = mnc ?: -1,
        lac = lac ?: -1,
        cid = cid ?: -1,
        estimatedLat = estimatedLat,
        estimatedLon = estimatedLon,
        estimatedAccuracyM = estimatedAccuracyM,
        geolocationStatus = geolocationStatus,
        geolocationError = geolocationError,
        towersCount = towersCount,
        towersJson = towersJson,
        deltaMs = deltaMs,
        sampleType = when {
            cid != null -> "cell"
            deltaMs != null -> "delta"
            else -> "attempt"
        },
        sipStatus = sipStatus,
        inviteMs = inviteElapsedMs,
        prMs = responseElapsedMs,
        intercarrierCandidate = intercarrierCandidate,
        probeId = attemptId,
        inviteSentAtMillis = inviteSentAtMillis,
        responseReceivedAtMillis = responseReceivedAtMillis,
        outcome = outcome,
        intervalSincePreviousProbeMs = intervalSincePreviousProbeMs,
        wifiRssiDbm = wifiRssiDbm,
        wifiFrequencyMhz = wifiFrequencyMhz,
        wifiLinkSpeedMbps = wifiLinkSpeedMbps,
        wifiBssidHash = wifiBssidHash,
        moving = moving,
        contractVersion = contractVersion,
        finishedAtMillis = finishedAtMillis,
        finishReason = finishReason
    )
}

private fun buildDeviceIdentifier(): String? {
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    val device = Build.DEVICE?.trim().orEmpty()
    val joined = listOf(manufacturer, model, device)
        .filter { it.isNotBlank() }
        .joinToString("_")
    return joined.ifBlank { null }
}

private fun jsonValue(value: Any?): Any {
    return value ?: JSONObject.NULL
}
