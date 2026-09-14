package com.example.cellidtracker.probe

import org.json.JSONObject

const val PROBE_EVENT_CONTRACT = "cellidtracker.probe"
const val PROBE_EVENT_CONTRACT_VERSION = 1

sealed interface StructuredProbeEvent {
    val contractVersion: Int
}

data class ProbeStreamReadyEvent(
    override val contractVersion: Int
) : StructuredProbeEvent

sealed interface ProbeAttemptEvent : StructuredProbeEvent {
    val attemptId: String
}

data class ProbeAttemptStartedEvent(
    override val contractVersion: Int,
    override val attemptId: String,
    val inviteElapsedMs: Long,
    val inviteUnixMs: Long
) : ProbeAttemptEvent

data class ProbeProvisionalReceivedEvent(
    override val contractVersion: Int,
    override val attemptId: String,
    val status: Int,
    val deltaMs: Long,
    val inviteElapsedMs: Long,
    val responseElapsedMs: Long,
    val inviteUnixMs: Long,
    val responseUnixMs: Long
) : ProbeAttemptEvent

data class ProbeCellObservedEvent(
    override val contractVersion: Int,
    override val attemptId: String,
    val status: Int,
    val deltaMs: Long,
    val inviteElapsedMs: Long,
    val responseElapsedMs: Long,
    val inviteUnixMs: Long,
    val responseUnixMs: Long,
    val parsedCell: ParsedCellFromLog
) : ProbeAttemptEvent

data class ProbeAttemptFinishedEvent(
    override val contractVersion: Int,
    override val attemptId: String,
    val finishedUnixMs: Long,
    val outcome: String,
    val reason: String
) : ProbeAttemptEvent

fun tryParseStructuredProbeEventFromStdoutLine(line: String): StructuredProbeEvent? {
    val trimmed = line.trim()
    if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return null

    return runCatching {
        val json = JSONObject(trimmed)
        if (json.optString("contract") != PROBE_EVENT_CONTRACT) return@runCatching null
        val version = json.requiredInt("version")
        if (version != PROBE_EVENT_CONTRACT_VERSION) return@runCatching null

        when (json.requiredString("event")) {
            "stream_ready" -> ProbeStreamReadyEvent(version)
            "attempt_started" -> ProbeAttemptStartedEvent(
                contractVersion = version,
                attemptId = json.requiredString("attempt_id"),
                inviteElapsedMs = json.requiredNonNegativeLong("invite_elapsed_ms"),
                inviteUnixMs = json.requiredNonNegativeLong("invite_unix_ms")
            )
            "provisional_received" -> json.parseProvisional(version)
            "cell_observed" -> {
                val provisional = json.parseProvisional(version)
                ProbeCellObservedEvent(
                    contractVersion = version,
                    attemptId = provisional.attemptId,
                    status = provisional.status,
                    deltaMs = provisional.deltaMs,
                    inviteElapsedMs = provisional.inviteElapsedMs,
                    responseElapsedMs = provisional.responseElapsedMs,
                    inviteUnixMs = provisional.inviteUnixMs,
                    responseUnixMs = provisional.responseUnixMs,
                    parsedCell = ParsedCellFromLog(
                        mcc = json.requiredNonNegativeInt("mcc"),
                        mnc = json.requiredNonNegativeInt("mnc"),
                        lac = json.requiredNonNegativeInt("lac"),
                        cid = json.requiredNonNegativeInt("cid")
                    )
                )
            }
            "attempt_finished" -> ProbeAttemptFinishedEvent(
                contractVersion = version,
                attemptId = json.requiredString("attempt_id"),
                finishedUnixMs = json.requiredNonNegativeLong("finished_unix_ms"),
                outcome = json.requiredString("outcome"),
                reason = json.requiredString("reason")
            )
            else -> null
        }
    }.getOrNull()
}

private fun JSONObject.parseProvisional(version: Int): ProbeProvisionalReceivedEvent {
    val status = requiredInt("status")
    require(status in 100..199) { "status must be provisional" }
    val deltaMs = requiredNonNegativeLong("delta_ms")
    val inviteElapsedMs = requiredNonNegativeLong("invite_elapsed_ms")
    val responseElapsedMs = requiredNonNegativeLong("response_elapsed_ms")
    val inviteUnixMs = requiredNonNegativeLong("invite_unix_ms")
    val responseUnixMs = requiredNonNegativeLong("response_unix_ms")
    require(responseElapsedMs - inviteElapsedMs == deltaMs) {
        "delta_ms must match monotonic timestamps"
    }

    return ProbeProvisionalReceivedEvent(
        contractVersion = version,
        attemptId = requiredString("attempt_id"),
        status = status,
        deltaMs = deltaMs,
        inviteElapsedMs = inviteElapsedMs,
        responseElapsedMs = responseElapsedMs,
        inviteUnixMs = inviteUnixMs,
        responseUnixMs = responseUnixMs
    )
}

private fun JSONObject.requiredString(name: String): String {
    return getString(name).trim().also { require(it.isNotEmpty()) { "$name is blank" } }
}

private fun JSONObject.requiredInt(name: String): Int = getInt(name)

private fun JSONObject.requiredNonNegativeInt(name: String): Int {
    return requiredInt(name).also { require(it >= 0) { "$name is negative" } }
}

private fun JSONObject.requiredNonNegativeLong(name: String): Long {
    return getLong(name).also { require(it >= 0) { "$name is negative" } }
}
