package com.example.cellidtracker

enum class CellMapTimelineItemType {
    ProbeStart,
    ProbeStop,
    ProbePoint
}

data class CellMapTimelineItem(
    val type: CellMapTimelineItemType,
    val timestampMillis: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val exitCode: Int? = null,
    val stoppedByUser: Boolean? = null
)
