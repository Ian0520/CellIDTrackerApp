package com.example.cellidtracker.probe

data class ProbeParseDeduperState(
    var cycleId: Long = 0,
    var lastAcceptedCycleId: Long = -1,
    var lastSignature: String? = null,
)

fun markProbeCycleBoundary(state: ProbeParseDeduperState) {
    state.cycleId += 1
}

fun shouldAcceptParsedCell(
    parsed: ParsedCellFromLog,
    state: ProbeParseDeduperState
): Boolean {
    val signature = "${parsed.mcc}:${parsed.mnc}:${parsed.lac}:${parsed.cid}"
    if (state.lastAcceptedCycleId == state.cycleId) {
        // Keep at most one accepted parse per probe cycle.
        return false
    }

    state.lastSignature = signature
    state.lastAcceptedCycleId = state.cycleId
    return true
}
