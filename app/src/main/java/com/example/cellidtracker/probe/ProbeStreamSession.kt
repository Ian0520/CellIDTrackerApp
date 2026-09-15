package com.example.cellidtracker.probe

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

internal data class ProbeLineEvents(
    val structuredEvent: StructuredProbeEvent? = null,
    val legacyDeltaEvent: ProbeDeltaEventFromNative? = null,
    val legacyCellEvent: ProbeEventFromNative? = null,
    val structuredQueueFailed: Boolean = false
)

/**
 * Owns all parsing and reduction state for one native process invocation.
 * A new instance must be created after every native process restart.
 */
internal class ProbeStreamSession(
    private val contextProvider: () -> ProbeAttemptContext,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val committedCallIds = hashSetOf<String>()
    private val committedDeltaKeys = hashSetOf<String>()
    private val structuredContractActive = AtomicBoolean(false)
    private val structuredEvents = Channel<StructuredProbeEnvelope>(Channel.UNLIMITED)
    private val reducer = ProbeAttemptReducer()
    private val contextLock = Any()
    private val contexts = linkedMapOf<String, ProbeAttemptContext>()

    val lastProbeEventAtMillis = AtomicLong(nowMillis())

    fun accept(line: String): ProbeLineEvents {
        val structuredEvent = tryParseStructuredProbeEventFromStdoutLine(line)
        if (structuredEvent != null) {
            if (structuredEvent is ProbeStreamReadyEvent) {
                structuredContractActive.set(true)
            }
            val context = if (structuredEvent is ProbeAttemptEvent) {
                contextFor(structuredEvent.attemptId)
            } else {
                null
            }
            val queued = structuredEvents.trySend(
                StructuredProbeEnvelope(event = structuredEvent, context = context)
            ).isSuccess
            return ProbeLineEvents(
                structuredEvent = structuredEvent,
                structuredQueueFailed = !queued
            )
        }

        if (structuredContractActive.get()) {
            // Structured output is authoritative; legacy lines remain diagnostics only.
            return ProbeLineEvents()
        }

        val deltaEvent = tryParseProbeDeltaEventFromStdoutLine(line)
            ?.takeIf(::commitLegacyDelta)
        val cellEvent = tryParseProbeEventFromStdoutLine(line)
            ?.takeIf(::commitLegacyCell)
        if (cellEvent != null) {
            lastProbeEventAtMillis.set(nowMillis())
        }
        return ProbeLineEvents(
            legacyDeltaEvent = deltaEvent,
            legacyCellEvent = cellEvent
        )
    }

    suspend fun consume(
        onAttemptChange: suspend (
            event: ProbeAttemptEvent,
            change: ProbeAttemptChange,
            context: ProbeAttemptContext
        ) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        for (envelope in structuredEvents) {
            val event = envelope.event
            if (event is ProbeStreamReadyEvent) continue
            event as ProbeAttemptEvent

            try {
                val change = reducer.apply(event) ?: continue
                val context = requireNotNull(envelope.context)
                if (event is ProbeCellObservedEvent && change.cellAdded) {
                    lastProbeEventAtMillis.set(nowMillis())
                }
                onAttemptChange(event, change, context)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                onFailure(error)
            } finally {
                if (event is ProbeAttemptFinishedEvent) {
                    releaseContext(event.attemptId)
                }
            }
        }
    }

    fun close() {
        structuredEvents.close()
    }

    private fun commitLegacyCell(event: ProbeEventFromNative): Boolean {
        if (committedCallIds.size >= MAX_TRACKED_EVENTS) {
            committedCallIds.clear()
        }
        return committedCallIds.add(event.callId)
    }

    private fun commitLegacyDelta(event: ProbeDeltaEventFromNative): Boolean {
        if (committedDeltaKeys.size >= MAX_TRACKED_EVENTS) {
            committedDeltaKeys.clear()
        }
        val key = "${event.inviteMs ?: -1}:${event.prMs ?: -1}:${event.deltaMs}:${event.status ?: -1}"
        return committedDeltaKeys.add(key)
    }

    private fun contextFor(attemptId: String): ProbeAttemptContext = synchronized(contextLock) {
        contexts[attemptId]?.let { return@synchronized it }
        if (contexts.size >= MAX_TRACKED_EVENTS) {
            contexts.remove(contexts.entries.first().key)
        }
        contextProvider().also { contexts[attemptId] = it }
    }

    private fun releaseContext(attemptId: String) {
        synchronized(contextLock) {
            contexts.remove(attemptId)
        }
    }

    private data class StructuredProbeEnvelope(
        val event: StructuredProbeEvent,
        val context: ProbeAttemptContext?
    )

    private companion object {
        const val MAX_TRACKED_EVENTS = 4096
    }
}
