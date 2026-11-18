package com.example.cellidtracker.experiment

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal data class SipReassemblySummary(
    val completeMessages: Long,
    val incompleteStreams: Long,
    val incompleteDatagrams: Long,
    val malformedMessages: Long,
    val duplicateTcpPackets: Long,
    val duplicateTcpBytes: Long,
    val conflictingTcpStreams: Long,
    val tcpGaps: Long,
    val missingContentLength: Long,
    val nativeDiscontinuities: Long,
    val limitExceeded: Long,
    val connectionBoundaryEvents: Long = 0,
    val inferredConnectionBoundaries: Long = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("completeMessages", completeMessages)
        .put("incompleteStreams", incompleteStreams)
        .put("incompleteDatagrams", incompleteDatagrams)
        .put("malformedMessages", malformedMessages)
        .put("duplicateTcpPackets", duplicateTcpPackets)
        .put("duplicateTcpBytes", duplicateTcpBytes)
        .put("conflictingTcpStreams", conflictingTcpStreams)
        .put("tcpGaps", tcpGaps)
        .put("missingContentLength", missingContentLength)
        .put("nativeDiscontinuities", nativeDiscontinuities)
        .put("limitExceeded", limitExceeded)
        .put("connectionBoundaryEvents", connectionBoundaryEvents)
        .put("inferredConnectionBoundaries", inferredConnectionBoundaries)
}

/**
 * Offline, bounded reconstruction from TCP byte positions, never from a Call-ID or text similarity.
 * Raw records remain evidence; only validated, complete messages leave this class.
 */
internal class SipMessageReassembler(private val onMessage: (JSONObject) -> Unit) {
    private data class FlowKey(val process: String, val tuple: List<String>)
    private data class Packet(val start: Long, val bytes: ByteArray, val metadata: JSONObject, val arrival: Long) {
        val end: Long get() = start + bytes.size
    }
    private data class Flow(
        val key: FlowKey,
        val anchor: Long,
        val packets: MutableList<Packet> = mutableListOf(),
        var bytes: Int = 0,
        var lastElapsed: Long = 0
    )
    private data class Region(val start: Long, var end: Long, val packets: MutableList<Packet>)

    private val flows = linkedMapOf<FlowKey, Flow>()
    private val lastSequence = mutableMapOf<String, Long>()
    private val reportedDrops = mutableMapOf<String, Long>()
    private val connectionEpochs = mutableMapOf<String, Long>()
    // A conflicting byte cannot be resolved without connection identity or TCP control flags.
    private val conflictedKeys = mutableSetOf<FlowKey>()
    private var totalBytes = 0
    private var arrival = 0L
    private var finished = false
    private var completeMessages = 0L
    private var incompleteStreams = 0L
    private var incompleteDatagrams = 0L
    private var malformedMessages = 0L
    private var duplicateTcpPackets = 0L
    private var duplicateTcpBytes = 0L
    private var conflictingTcpStreams = 0L
    private var tcpGaps = 0L
    private var missingContentLength = 0L
    private var nativeDiscontinuities = 0L
    private var limitExceeded = 0L
    private var connectionBoundaryEvents = 0L
    private var inferredConnectionBoundaries = 0L

    fun accept(record: JSONObject) {
        check(!finished) { "Reassembly has already finished" }
        val process = record.optString("run_id") + ":" + record.optLong("process_id")
        val event = record.optString("event")
        val sequence = record.optLong("sequence", -1)
        if (event == "process_started") {
            flushProcess(process)
            conflictedKeys.removeAll { it.process == process }
            lastSequence.remove(process)
            reportedDrops.remove(process)
            connectionEpochs[process] = 0L
        }
        val previous = lastSequence[process]
        if (sequence >= 0) {
            if ((previous != null && sequence != previous + 1) || (previous == null && sequence > 1)) {
                nativeDiscontinuities++
                if (flows.keys.any { it.process == process }) inferredConnectionBoundaries++
                flushProcess(process)
            }
            lastSequence[process] = sequence
        }
        val drops = record.optLong("dropped_event_count", 0)
        if (drops > (reportedDrops[process] ?: 0L)) {
            nativeDiscontinuities++
            if (flows.keys.any { it.process == process }) inferredConnectionBoundaries++
            flushProcess(process)
            reportedDrops[process] = drops
        }
        if (record.optString("record_type") == "runtime_event" && event == "tcp_connection_boundary") {
            // SYN is recorded before its payload and FIN after it. No byte range may cross either.
            // Endpoints are unavailable in this event, so a process-wide boundary is conservative.
            flushProcess(process)
            conflictedKeys.removeAll { it.process == process }
            connectionEpochs[process] = (connectionEpochs[process] ?: 0L) + 1
            connectionBoundaryEvents++
        }
        if (event == "process_finished" || event == "capture_summary") {
            flushProcess(process)
        }
        if (record.optString("record_type") != "transport_payload") return
        val encoded = record.optString("payload_base64")
        if (encoded.isEmpty()) return
        // The encoded length check avoids allocating arbitrarily large untrusted records.
        if (encoded.length > (MAX_FLOW_BYTES * 4L / 3L + 8)) {
            limitExceeded++
            incompleteDatagrams++
            return
        }
        val payload = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            malformedMessages++
            incompleteDatagrams++
            return
        }
        if (payload.isEmpty()) return
        val metadata = snapshotMetadata(record).put("connection_epoch", connectionEpochs[process] ?: 0L)
        val capturePoint = record.optString("capture_point")
        val transport = record.optString("transport").lowercase()
        if (capturePoint == "application_send" || transport == "udp") {
            val parsed = SipMessageFraming.parseComplete(payload, requireContentLength = transport != "udp")
            if (parsed == null) {
                classifyFailure(SipMessageFraming.inspect(payload, requireContentLength = transport != "udp"))
                incompleteDatagrams++
            } else {
                emit(parsed, listOf(Packet(0, payload, metadata, arrival++)), 0)
            }
            return
        }
        if (transport != "tcp" || capturePoint != "decoded_transport" || !record.has("tcp_sequence")) {
            incompleteDatagrams++
            return
        }
        val key = FlowKey(process, listOf(
            record.optString("direction"), capturePoint, transport,
            record.optString("ip_version"), record.optString("source_address"),
            record.optString("source_port"), record.optString("destination_address"),
            record.optString("destination_port")
        ))
        if (key in conflictedKeys) return
        val elapsed = record.optLong("elapsed_ns", 0)
        // A boundary prevents a later connection from completing an old unfinished message.
        flows.values.filter { it.key.process == process && elapsed > it.lastElapsed &&
            elapsed - it.lastElapsed > IDLE_TIMEOUT_NS }.map { it.key }.forEach {
            inferredConnectionBoundaries++
            flush(it)
        }
        var flow = flows[key]
        if (flow != null && (flow.bytes + payload.size > MAX_FLOW_BYTES || flow.packets.size >= MAX_FLOW_PACKETS)) {
            limitExceeded++
            inferredConnectionBoundaries++
            flush(key)
            flow = null
        }
        while (flows.isNotEmpty() && (totalBytes + payload.size > MAX_TOTAL_BYTES ||
                    (flow == null && flows.size >= MAX_FLOWS))) {
            limitExceeded++
            inferredConnectionBoundaries++
            flush(flows.values.minBy { it.lastElapsed }.key)
            flow = flows[key]
        }
        val sequence32 = record.optLong("tcp_sequence") and 0xffffffffL
        if (flow == null) {
            flow = Flow(key, sequence32)
            flows[key] = flow
        }
        val relative = ((sequence32 - flow.anchor + 0x80000000L) and 0xffffffffL) - 0x80000000L
        if (relative == -0x80000000L) {
            conflictingTcpStreams++
            incompleteStreams++
            conflictedKeys += key
            discard(key)
            return
        }
        flow.packets += Packet(flow.anchor + relative, payload, metadata, arrival++)
        flow.bytes += payload.size
        flow.lastElapsed = elapsed
        totalBytes += payload.size
    }

    fun finish(): SipReassemblySummary {
        if (!finished) {
            flows.keys.toList().forEach(::flush)
            finished = true
        }
        return SipReassemblySummary(
            completeMessages, incompleteStreams, incompleteDatagrams, malformedMessages,
            duplicateTcpPackets, duplicateTcpBytes, conflictingTcpStreams, tcpGaps,
            missingContentLength, nativeDiscontinuities, limitExceeded,
            connectionBoundaryEvents, inferredConnectionBoundaries
        )
    }

    private fun flushProcess(process: String) {
        flows.keys.filter { it.process == process }.forEach(::flush)
    }

    private fun discard(key: FlowKey): Flow? = flows.remove(key)?.also { totalBytes -= it.bytes }

    private fun flush(key: FlowKey) {
        val flow = discard(key) ?: return
        val regions = mutableListOf<Region>()
        for (packet in flow.packets.sortedWith(compareBy<Packet> { it.start }.thenBy { it.arrival })) {
            val last = regions.lastOrNull()
            if (last == null || packet.start > last.end) {
                if (last != null) tcpGaps++
                regions += Region(packet.start, packet.end, mutableListOf(packet))
            } else {
                last.end = maxOf(last.end, packet.end)
                last.packets += packet
            }
        }
        val assembled = mutableListOf<Pair<Region, ByteArray>>()
        var conflicted = false
        for (region in regions) {
            val bytes = ByteArray((region.end - region.start).toInt())
            var filled = 0
            for (packet in region.packets) {
                val offset = (packet.start - region.start).toInt()
                val overlap = minOf(packet.bytes.size, filled - offset).coerceAtLeast(0)
                for (index in 0 until overlap) {
                    if (bytes[offset + index] != packet.bytes[index]) conflicted = true
                }
                duplicateTcpBytes += overlap
                if (overlap == packet.bytes.size) duplicateTcpPackets++
                if (overlap < packet.bytes.size) {
                    packet.bytes.copyInto(bytes, offset + overlap, overlap)
                    filled = maxOf(filled, offset + packet.bytes.size)
                }
            }
            assembled += region to bytes
        }
        if (conflicted) {
            conflictingTcpStreams++
            incompleteStreams++
            conflictedKeys += key
            return
        }
        var incomplete = regions.size > 1
        for ((region, bytes) in assembled) {
            var offset = 0
            while (offset < bytes.size) {
                // RFC 3261 permits CRLF keepalives between SIP messages on a connection.
                if (offset + 1 < bytes.size && bytes[offset] == 13.toByte() && bytes[offset + 1] == 10.toByte()) {
                    offset += 2
                    continue
                }
                val result = SipMessageFraming.inspect(bytes.copyOfRange(offset, bytes.size), true)
                if (result !is SipMessageFraming.Inspection.Complete) {
                    classifyFailure(result)
                    incomplete = true
                    break
                }
                emit(result.message, region.packets, region.start + offset)
                offset += result.message.payload.size
            }
        }
        if (incomplete) incompleteStreams++
    }

    private fun classifyFailure(result: SipMessageFraming.Inspection) {
        when (result) {
            SipMessageFraming.Inspection.Malformed -> malformedMessages++
            SipMessageFraming.Inspection.MissingLength -> missingContentLength++
            SipMessageFraming.Inspection.TooLarge -> limitExceeded++
            else -> Unit
        }
    }

    private fun emit(message: ParsedSipMessage, packets: List<Packet>, start: Long) {
        val end = start + message.payload.size
        val contributing = packets.filter { it.start < end && it.end > start }.sortedBy { it.arrival }
        val first = contributing.first { it.start <= start && it.end > start }
        var completed = first
        val covered = mutableListOf<Pair<Long, Long>>()
        for (packet in contributing) {
            covered += maxOf(start, packet.start) to minOf(end, packet.end)
            var until = start
            for ((left, right) in covered.sortedBy { it.first }) {
                if (left > until) break
                until = maxOf(until, right)
            }
            if (until >= end) {
                completed = packet
                break
            }
        }
        fun header(name: String, compact: String = ""): String = message.headers.firstOrNull {
            it.first.equals(name, true) || (compact.isNotEmpty() && it.first.equals(compact, true))
        }?.second.orEmpty()
        fun parameter(value: String, name: String): String =
            Regex("(?:^|;)\\s*${Regex.escape(name)}\\s*=\\s*([^;\\s,>]+)", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.get(1)?.trim('"').orEmpty()
        val cseq = header("CSeq").trim().split(Regex("\\s+"))
        val record = JSONObject(first.metadata.toString())
            .put("record_type", "sip_message")
            .put("capture_format_version", 1)
            .put("recognized_sip", true)
            .put("complete", true)
            .put("start_line", message.startLine)
            .put("method", message.method)
            .put("status", message.status)
            .put("reason", message.reason)
            .put("call_id", header("Call-ID", "i"))
            .put("via_branch", parameter(header("Via", "v"), "branch"))
            .put("from_tag", parameter(header("From", "f"), "tag"))
            .put("to_tag", parameter(header("To", "t"), "tag"))
            .put("cseq", cseq.firstOrNull().orEmpty())
            .put("cseq_method", cseq.getOrNull(1).orEmpty())
            .put("first_byte_unix_ms", first.metadata.optLong("unix_ms"))
            .put("first_byte_elapsed_ns", first.metadata.optLong("elapsed_ns"))
            .put("completed_unix_ms", completed.metadata.optLong("unix_ms"))
            .put("completed_elapsed_ns", completed.metadata.optLong("elapsed_ns"))
            .put("contributing_sequences", JSONArray(contributing.map { it.metadata.optLong("sequence") }))
            .put("contributing_packet_count", contributing.size)
            .put("payload_size", message.payload.size)
            .put("body_size", message.body.size)
            .put("headers", JSONArray(message.headers.map { (name, value) ->
                JSONObject().put("name", name).put("value", value)
            }))
            .put("payload_base64", Base64.getEncoder().encodeToString(message.payload))
            .put("body_base64", Base64.getEncoder().encodeToString(message.body))
        onMessage(record)
        completeMessages++
    }

    private fun snapshotMetadata(record: JSONObject): JSONObject = JSONObject().also { snapshot ->
        listOf("run_id", "process_id", "sequence", "unix_ms", "elapsed_ns", "direction", "capture_point",
            "transport", "ip_version", "source_address", "destination_address", "source_port",
            "destination_port").forEach { key -> if (record.has(key)) snapshot.put(key, record.opt(key)) }
    }

    private companion object {
        const val MAX_FLOW_BYTES = 1024 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
        const val MAX_FLOW_PACKETS = 4096
        const val MAX_FLOWS = 64
        const val IDLE_TIMEOUT_NS = 60_000_000_000L
    }
}
