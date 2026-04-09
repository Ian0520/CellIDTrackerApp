package com.example.cellidtracker.probe

private const val DEFAULT_DUPLICATE_WINDOW_MILLIS = 10_000L

data class ProbeParseDeduperState(
    var lastSignature: String? = null,
    var lastAcceptedAtMillis: Long = 0
)

fun shouldAcceptParsedCell(
    parsed: ParsedCellFromLog,
    nowMillis: Long,
    state: ProbeParseDeduperState,
    duplicateWindowMillis: Long = DEFAULT_DUPLICATE_WINDOW_MILLIS
): Boolean {
    val signature = "${parsed.mcc}:${parsed.mnc}:${parsed.lac}:${parsed.cid}"
    val previousSignature = state.lastSignature
    val elapsedMillis = nowMillis - state.lastAcceptedAtMillis

    val isDuplicateInsideWindow = previousSignature == signature &&
        elapsedMillis >= 0 &&
        elapsedMillis < duplicateWindowMillis

    if (isDuplicateInsideWindow) {
        return false
    }

    state.lastSignature = signature
    state.lastAcceptedAtMillis = nowMillis
    return true
}
