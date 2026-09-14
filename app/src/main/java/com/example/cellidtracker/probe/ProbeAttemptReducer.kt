package com.example.cellidtracker.probe

data class ProbeAttemptSnapshot(
    val attemptId: String,
    val contractVersion: Int,
    val inviteElapsedMs: Long? = null,
    val inviteUnixMs: Long? = null,
    val responseElapsedMs: Long? = null,
    val responseUnixMs: Long? = null,
    val status: Int? = null,
    val deltaMs: Long? = null,
    val parsedCell: ParsedCellFromLog? = null,
    val finishedUnixMs: Long? = null,
    val outcome: String = "started",
    val finishReason: String? = null
)

data class ProbeAttemptChange(
    val snapshot: ProbeAttemptSnapshot,
    val created: Boolean,
    val provisionalAdded: Boolean = false,
    val cellAdded: Boolean = false,
    val finishAdded: Boolean = false
)

class ProbeAttemptReducer(
    private val maxTrackedAttempts: Int = 4096
) {
    private val attempts = LinkedHashMap<String, ProbeAttemptSnapshot>()

    init {
        require(maxTrackedAttempts > 0)
    }

    fun apply(event: StructuredProbeEvent): ProbeAttemptChange? {
        if (event !is ProbeAttemptEvent) return null
        val previous = attempts[event.attemptId]
        val change = when (event) {
            is ProbeAttemptStartedEvent -> reduceStarted(previous, event)
            is ProbeProvisionalReceivedEvent -> reduceProvisional(previous, event)
            is ProbeCellObservedEvent -> reduceCell(previous, event)
            is ProbeAttemptFinishedEvent -> reduceFinished(previous, event)
        } ?: return null

        attempts[event.attemptId] = change.snapshot
        trimToLimit()
        return change
    }

    fun snapshot(attemptId: String): ProbeAttemptSnapshot? = attempts[attemptId]

    fun clear() {
        attempts.clear()
    }

    private fun reduceStarted(
        previous: ProbeAttemptSnapshot?,
        event: ProbeAttemptStartedEvent
    ): ProbeAttemptChange? {
        if (previous == null) {
            return ProbeAttemptChange(
                snapshot = ProbeAttemptSnapshot(
                    attemptId = event.attemptId,
                    contractVersion = event.contractVersion,
                    inviteElapsedMs = event.inviteElapsedMs,
                    inviteUnixMs = event.inviteUnixMs
                ),
                created = true
            )
        }
        if (previous.inviteElapsedMs != null || previous.inviteUnixMs != null) return null
        return ProbeAttemptChange(
            snapshot = previous.copy(
                inviteElapsedMs = event.inviteElapsedMs,
                inviteUnixMs = event.inviteUnixMs
            ),
            created = false
        )
    }

    private fun reduceProvisional(
        previous: ProbeAttemptSnapshot?,
        event: ProbeProvisionalReceivedEvent
    ): ProbeAttemptChange? {
        if (previous?.responseElapsedMs != null) return null
        val base = previous ?: ProbeAttemptSnapshot(
            attemptId = event.attemptId,
            contractVersion = event.contractVersion
        )
        return ProbeAttemptChange(
            snapshot = base.copy(
                inviteElapsedMs = base.inviteElapsedMs ?: event.inviteElapsedMs,
                inviteUnixMs = base.inviteUnixMs ?: event.inviteUnixMs,
                responseElapsedMs = event.responseElapsedMs,
                responseUnixMs = event.responseUnixMs,
                status = event.status,
                deltaMs = event.deltaMs,
                outcome = "provisional_received"
            ),
            created = previous == null,
            provisionalAdded = true
        )
    }

    private fun reduceCell(
        previous: ProbeAttemptSnapshot?,
        event: ProbeCellObservedEvent
    ): ProbeAttemptChange? {
        if (previous?.parsedCell != null) return null
        val base = previous ?: ProbeAttemptSnapshot(
            attemptId = event.attemptId,
            contractVersion = event.contractVersion
        )
        return ProbeAttemptChange(
            snapshot = base.copy(
                inviteElapsedMs = base.inviteElapsedMs ?: event.inviteElapsedMs,
                inviteUnixMs = base.inviteUnixMs ?: event.inviteUnixMs,
                responseElapsedMs = base.responseElapsedMs ?: event.responseElapsedMs,
                responseUnixMs = base.responseUnixMs ?: event.responseUnixMs,
                status = base.status ?: event.status,
                deltaMs = base.deltaMs ?: event.deltaMs,
                parsedCell = event.parsedCell,
                outcome = "cell_observed"
            ),
            created = previous == null,
            provisionalAdded = previous?.responseElapsedMs == null,
            cellAdded = true
        )
    }

    private fun reduceFinished(
        previous: ProbeAttemptSnapshot?,
        event: ProbeAttemptFinishedEvent
    ): ProbeAttemptChange? {
        if (previous?.finishedUnixMs != null) return null
        val base = previous ?: ProbeAttemptSnapshot(
            attemptId = event.attemptId,
            contractVersion = event.contractVersion
        )
        return ProbeAttemptChange(
            snapshot = base.copy(
                finishedUnixMs = event.finishedUnixMs,
                outcome = event.outcome,
                finishReason = event.reason
            ),
            created = previous == null,
            finishAdded = true
        )
    }

    private fun trimToLimit() {
        while (attempts.size > maxTrackedAttempts) {
            val eldest = attempts.entries.firstOrNull()?.key ?: return
            attempts.remove(eldest)
        }
    }
}
