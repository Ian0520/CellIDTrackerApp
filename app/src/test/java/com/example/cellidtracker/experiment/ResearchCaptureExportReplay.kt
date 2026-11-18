package com.example.cellidtracker.experiment

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Run the actual export pipeline on locally held private evidence without Android or a phone. */
internal object ResearchCaptureExportReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected raw native directory and new destination directory" }
        val source = File(args[0])
        val destination = File(args[1])
        require(!destination.exists()) { "Replay output must be a new directory" }
        val result = exportNativeCapture(
            source,
            destination,
            CaptureSanitizer(CampaignPseudonymizer(ByteArray(32) { it.toByte() }))
        )
        val summary = JSONObject()
            .put("auditOnlyKey", true)
            .put("sourceEvidenceUnmodified", true)
            .put("nativeCaptureRecords", result.packetRecords)
            .put("sanitizationFailures", result.sanitizationFailures)
            .put("nativeReportedDroppedEvents", result.reportedNativeDroppedEvents)
            .put("nativePacketQualityIssues", result.packetQualityIssues)
            .put("sipMessageReassembly", result.reassembly.toJson())
            .put("files", JSONArray().apply {
                result.files.forEach { file ->
                    put(JSONObject().put("path", file.name).put("bytes", file.length())
                        .put("sha256", MessageDigest.getInstance("SHA-256")
                            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }))
                }
            })
        File(destination, "audit_manifest.json").writeText(summary.toString(2))
        println(summary.toString(2))
        check(result.sanitizationFailures == 0L) { "Some native records could not be exported" }
    }
}
