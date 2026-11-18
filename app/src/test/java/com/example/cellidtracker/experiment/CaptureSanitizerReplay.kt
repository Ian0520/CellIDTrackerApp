package com.example.cellidtracker.experiment

import java.io.File

/** Offline audit utility: a deterministic audit key keeps replay artifacts separate from app exports. */
internal object CaptureSanitizerReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected input JSONL and output JSONL paths" }
        val source = File(args[0])
        val destination = File(args[1])
        require(source.canonicalFile != destination.canonicalFile) { "Replay must preserve its input" }
        val sanitizer = CaptureSanitizer(CampaignPseudonymizer(ByteArray(32) { it.toByte() }))
        val result = sanitizer.sanitizeJsonLines(source, destination)
        check(result.recordsDropped == 0L) { "Replay contained malformed or unsanitizable records" }
        println("Sanitized ${result.recordsSanitized} replay records; ${result.recordsDropped} failures.")
    }
}
