package com.example.cellidtracker.probe

import java.util.concurrent.atomic.AtomicLong

internal class ProbeResponseIntervalTracker {
    private val lastResponseAtMillis = AtomicLong(NO_RESPONSE)

    fun record(responseReceivedAtMillis: Long): Long? {
        val previous = lastResponseAtMillis.getAndSet(responseReceivedAtMillis)
        return previous.takeIf { it > 0L && responseReceivedAtMillis >= it }
            ?.let { responseReceivedAtMillis - it }
    }

    fun reset() {
        lastResponseAtMillis.set(NO_RESPONSE)
    }

    private companion object {
        const val NO_RESPONSE = -1L
    }
}
