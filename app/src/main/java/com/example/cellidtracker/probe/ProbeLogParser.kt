package com.example.cellidtracker.probe

data class ParsedCellFromLog(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int
)

fun tryParseCellFromStdoutLine(
    line: String,
    acc: MutableMap<String, Int>
): ParsedCellFromLog? {
    val regex = Regex("(mcc|mnc|lac|cellid|cid)\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    regex.findAll(line).forEach { match ->
        val key = match.groupValues[1].lowercase()
        val value = match.groupValues[2].toIntOrNull() ?: return@forEach
        when (key) {
            "mcc" -> acc["mcc"] = value
            "mnc" -> acc["mnc"] = value
            "lac" -> acc["lac"] = value
            "cellid", "cid" -> acc["cellid"] = value
        }
    }

    val mcc = acc["mcc"]
    val mnc = acc["mnc"]
    val lac = acc["lac"]
    val cid = acc["cellid"]

    return if (mcc != null && mnc != null && lac != null && cid != null) {
        // Reset after emitting one complete sample so stale fields
        // do not repeatedly re-trigger identical parses.
        acc.clear()
        ParsedCellFromLog(mcc, mnc, lac, cid)
    } else {
        null
    }
}
