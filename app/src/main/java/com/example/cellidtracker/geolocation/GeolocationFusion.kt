package com.example.cellidtracker.geolocation

import com.example.cellidtracker.CellLocationResult
import com.example.cellidtracker.CellTowerParams
import com.example.cellidtracker.GoogleGeolocationClient

private fun CellTowerParams.siteGroupingKey(): String {
    val radio = radioType.lowercase()
    return when (radio) {
        // Google documents LTE cellId as ECI = eNBId << 8 | sectorId.
        // Same upper 20 bits therefore indicate the same physical LTE site.
        "lte" -> "lte:$mcc:$mnc:$lac:${cid ushr 8}"
        else -> "$radio:$mcc:$mnc:$lac:$cid"
    }
}

private fun groupTowersBySite(
    towers: List<CellTowerParams>
): List<List<CellTowerParams>> {
    val groups = linkedMapOf<String, MutableList<CellTowerParams>>()
    towers.forEach { tower ->
        groups.getOrPut(tower.siteGroupingKey()) { mutableListOf() }.add(tower)
    }
    return groups.values.toList()
}

private fun buildRepresentativeCombos(
    groups: List<List<CellTowerParams>>,
    index: Int = 0,
    current: MutableList<CellTowerParams> = mutableListOf(),
    out: MutableList<List<CellTowerParams>> = mutableListOf()
): List<List<CellTowerParams>> {
    if (index >= groups.size) {
        out.add(current.toList())
        return out
    }
    groups[index].forEach { tower ->
        current.add(tower)
        buildRepresentativeCombos(groups, index + 1, current, out)
        current.removeAt(current.lastIndex)
    }
    return out
}

fun buildCandidatePayloads(
    towers: List<CellTowerParams>
): Pair<List<List<CellTowerParams>>, Int> {
    if (towers.isEmpty()) return emptyList<List<CellTowerParams>>() to 0

    val groups = groupTowersBySite(towers)
    val representativeCombos = buildRepresentativeCombos(groups)
    val seen = mutableSetOf<String>()
    val payloads = mutableListOf<List<CellTowerParams>>()

    representativeCombos.forEach { combo ->
        combo.indices.forEach { i ->
            val rotated = listOf(combo[i]) + combo.filterIndexed { idx, _ -> idx != i }
            val signature = rotated.joinToString("|") { "${it.radioType}:${it.mcc}:${it.mnc}:${it.lac}:${it.cid}" }
            if (seen.add(signature)) {
                payloads.add(rotated)
            }
        }
    }
    return payloads to groups.size
}

suspend fun selectBestLocation(
    towers: List<CellTowerParams>
): Pair<Result<CellLocationResult>, List<CellTowerParams>> {
    if (towers.isEmpty()) {
        return Result.failure<CellLocationResult>(IllegalArgumentException("No towers")).let { it to emptyList() }
    }

    val (payloads, _) = buildCandidatePayloads(towers)
    var best: CellLocationResult? = null
    var bestPayload: List<CellTowerParams> = emptyList()
    var firstFailure: Result<CellLocationResult>? = null

    for (payload in payloads) {
        val res = GoogleGeolocationClient.queryByCells(payload)
        if (res.isSuccess) {
            val loc = res.getOrNull()
            if (loc != null) {
                val acc = loc.range ?: Double.MAX_VALUE
                val bestAcc = best?.range ?: Double.MAX_VALUE
                if (best == null || acc < bestAcc) {
                    best = loc
                    bestPayload = payload
                }
            }
        } else if (firstFailure == null) {
            firstFailure = res
        }
    }

    return if (best != null) {
        Result.success(best) to bestPayload
    } else {
        (firstFailure ?: Result.failure(Exception("All geolocation attempts failed"))) to towers
    }
}
