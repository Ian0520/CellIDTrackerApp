package com.example.cellidtracker.probe

data class ProbeEventFromNative(
    val callId: String,
    val status: Int,
    val deltaMs: Long,
    val inviteMs: Long,
    val prMs: Long,
    val parsedCell: ParsedCellFromLog
)

data class ProbeDeltaEventFromNative(
    val status: Int?,
    val deltaMs: Long,
    val inviteMs: Long?,
    val prMs: Long?
)

private val PROBE_EVENT_REGEX = Regex(
    """^\[probe_event\]\s+call_id=([^\s]+)\s+status=(\d+)\s+delta_ms=(\d+)\s+invite_ms=(\d+)\s+pr_ms=(\d+)\s+mcc=(\d+)\s+mnc=(\d+)\s+lac=(\d+)\s+cid=(\d+)\s*$"""
)

private val DELTA_FIELD_REGEX = Regex("""\b(status|delta_ms|invite|pr)=([0-9]+)\b""")

fun tryParseProbeEventFromStdoutLine(line: String): ProbeEventFromNative? {
    val match = PROBE_EVENT_REGEX.matchEntire(line.trim()) ?: return null
    val callId = match.groupValues[1]
    val status = match.groupValues[2].toIntOrNull() ?: return null
    val deltaMs = match.groupValues[3].toLongOrNull() ?: return null
    val inviteMs = match.groupValues[4].toLongOrNull() ?: return null
    val prMs = match.groupValues[5].toLongOrNull() ?: return null
    val mcc = match.groupValues[6].toIntOrNull() ?: return null
    val mnc = match.groupValues[7].toIntOrNull() ?: return null
    val lac = match.groupValues[8].toIntOrNull() ?: return null
    val cid = match.groupValues[9].toIntOrNull() ?: return null

    return ProbeEventFromNative(
        callId = callId,
        status = status,
        deltaMs = deltaMs,
        inviteMs = inviteMs,
        prMs = prMs,
        parsedCell = ParsedCellFromLog(mcc = mcc, mnc = mnc, lac = lac, cid = cid)
    )
}

fun tryParseProbeDeltaEventFromStdoutLine(line: String): ProbeDeltaEventFromNative? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("[intercarrier]")) return null
    val fields = DELTA_FIELD_REGEX.findAll(trimmed).associate { match ->
        match.groupValues[1] to match.groupValues[2]
    }
    val deltaMs = fields["delta_ms"]?.toLongOrNull() ?: return null
    return ProbeDeltaEventFromNative(
        status = fields["status"]?.toIntOrNull(),
        deltaMs = deltaMs,
        inviteMs = fields["invite"]?.toLongOrNull(),
        prMs = fields["pr"]?.toLongOrNull()
    )
}
