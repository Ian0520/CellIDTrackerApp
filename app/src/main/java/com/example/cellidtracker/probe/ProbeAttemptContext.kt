package com.example.cellidtracker.probe

import com.example.cellidtracker.ProbeNetworkSnapshot

internal data class ProbeAttemptContext(
    val sessionDbId: Long?,
    val probeRunId: Long?,
    val victim: String,
    val moving: Boolean,
    val network: ProbeNetworkSnapshot
) {
    fun keyFor(attemptId: String): String {
        return "${sessionDbId ?: 0}:${probeRunId ?: 0}:$attemptId"
    }
}
