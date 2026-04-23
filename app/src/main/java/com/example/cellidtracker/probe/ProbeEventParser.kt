package com.example.cellidtracker.probe

data class ProbeEventFromNative(
    val callId: String,
    val status: Int,
    val deltaMs: Long,
    val inviteMs: Long,
    val prMs: Long,
    val parsedCell: ParsedCellFromLog
)

private val PROBE_EVENT_REGEX = Regex(
    """^\[probe_event\]\s+call_id=([^\s]+)\s+status=(\d+)\s+delta_ms=(\d+)\s+invite_ms=(\d+)\s+pr_ms=(\d+)\s+mcc=(\d+)\s+mnc=(\d+)\s+lac=(\d+)\s+cid=(\d+)\s*$"""
)

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
