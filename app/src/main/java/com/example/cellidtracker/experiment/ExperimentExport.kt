package com.example.cellidtracker.experiment

import android.content.Context
import android.os.Build
import com.example.cellidtracker.BuildConfig
import com.example.cellidtracker.data.ExperimentSampleEntity
import com.example.cellidtracker.data.ExperimentSessionEntity
import com.example.cellidtracker.data.HistoryDatabase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val PROBE_SESSION_EXPORT_SCHEMA_VERSION = 1
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
    val moving: Boolean
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
    exportedAtMillis: Long,
    appInfo: ProbeExportAppInfo
): ProbeSessionExportPayload {
    val sortedSamples = samples.sortedWith(compareBy<ExperimentSampleEntity> { it.recordedAtMillis }.thenBy { it.id })

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
        samples = sortedSamples.map { sample ->
            ProbeSessionSampleExportPayload(
                recordedAtMillis = sample.recordedAtMillis,
                victim = sample.victim,
                mcc = sample.mcc,
                mnc = sample.mnc,
                lac = sample.lac,
                cid = sample.cid,
                estimatedLat = sample.estimatedLat,
                estimatedLon = sample.estimatedLon,
                estimatedAccuracyM = sample.estimatedAccuracyM,
                geolocationStatus = sample.geolocationStatus,
                geolocationError = sample.geolocationError,
                towersCount = sample.towersCount,
                towersJson = sample.towersJson,
                deltaMs = sample.deltaMs,
                moving = sample.moving
            )
        }
    )
}

fun buildExperimentSessionExportJson(
    session: ExperimentSessionEntity,
    samples: List<ExperimentSampleEntity>,
    exportedAtMillis: Long,
    appInfo: ProbeExportAppInfo
): JSONObject {
    val payload = buildExperimentSessionExportPayload(
        session = session,
        samples = samples,
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
        .put("moving", sample.moving)
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
