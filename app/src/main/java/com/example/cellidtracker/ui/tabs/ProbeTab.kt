package com.example.cellidtracker.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ScrollState
import com.example.cellidtracker.CellLocationResult
import com.example.cellidtracker.CellMapMode
import com.example.cellidtracker.CellMapProbePoint
import com.example.cellidtracker.CellMapTimelineItem
import com.example.cellidtracker.CellMapTimelineItemType
import com.example.cellidtracker.CellMapView
import com.example.cellidtracker.ui.components.SmallInfoChip
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val MAP_PROBE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'GMT+8'")
        .withZone(ZoneOffset.ofHours(8))
private const val DISPLAYED_ACCURACY_SHRINK_FACTOR = 0.55

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProbeTabContent(
    probeColumnScrollState: ScrollState,
    activeExperimentSessionId: String?,
    activeExperimentStartedAtMillis: Long?,
    onStartExperimentSession: () -> Unit,
    onStopExperimentSession: () -> Unit,
    victimInput: String,
    onVictimInputChange: (String) -> Unit,
    isMoving: Boolean,
    onMovingChange: (Boolean) -> Unit,
    autoRestartProbe: Boolean,
    onAutoRestartProbeChange: (Boolean) -> Unit,
    probeIntervalSeconds: Int,
    onProbeIntervalSecondsChange: (Int) -> Unit,
    sessionProgressResponseLimit: Int?,
    onSessionProgressResponseLimitChange: (Int?) -> Unit,
    onSetVictimNumber: () -> Unit,
    isRootRunning: Boolean,
    isIntercarrierRunning: Boolean,
    mccInput: String,
    mncInput: String,
    lacInput: String,
    cidInput: String,
    cellLocation: CellLocationResult?,
    cellMapMode: CellMapMode,
    recentProbePoints: List<CellMapProbePoint>,
    allHistoryTimelineItems: List<CellMapTimelineItem>,
    onCellMapModeChange: (CellMapMode) -> Unit,
    intercarrierStatus: String,
    onStartProbe: () -> Unit,
    onStopProbe: () -> Unit,
    onStartIntercarrierTest: () -> Unit,
    onStopIntercarrierTest: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(probeColumnScrollState)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Experiment Session", style = MaterialTheme.typography.titleMedium)
                if (activeExperimentSessionId == null) {
                    Text(
                        "Start a session to generate an automatic date-time ID and collect experiment samples.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Session ID: $activeExperimentSessionId",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val startedAtText = activeExperimentStartedAtMillis
                        ?.let { Instant.ofEpochMilli(it).toString() }
                        ?: "N/A"
                    Text(
                        "Started: $startedAtText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartExperimentSession,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = activeExperimentSessionId == null
                    ) { Text("Start session") }

                    Button(
                        onClick = onStopExperimentSession,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = activeExperimentSessionId != null
                    ) { Text("Stop & export") }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Victim", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = victimInput,
                    onValueChange = onVictimInputChange,
                    label = { Text("Victim number") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Replaces /data/local/tmp/victim_list with this number") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Moving (for paging test)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isMoving, onCheckedChange = onMovingChange)
                }
                Button(
                    onClick = onSetVictimNumber,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Set victim number") }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Probe", style = MaterialTheme.typography.titleMedium)
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(if (isRootRunning) "Running" else "Idle") },
                        leadingIcon = null
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Latest probed result", style = MaterialTheme.typography.labelLarge)
                    if (mccInput.isNotBlank() || mncInput.isNotBlank() || lacInput.isNotBlank() || cidInput.isNotBlank()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SmallInfoChip("MCC", mccInput)
                            SmallInfoChip("MNC", mncInput)
                            SmallInfoChip("LAC", lacInput)
                            SmallInfoChip("CID", cidInput)
                        }
                        val loc = cellLocation
                        if (loc != null) {
                            Text(
                                buildString {
                                    append("Location: lat=${loc.lat}, lon=${loc.lon}")
                                    formatDisplayedAccuracyMeters(loc.range)?.let {
                                        append(" · accuracy=$it")
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Text(
                                "Location not available yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            "No cell parsed yet. Press Probe to start.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        intercarrierStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    "183 responses before rollover",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "The count includes the first 183. Carrier default is CHT 6, TWM/FET 4.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = sessionProgressResponseLimit == null,
                        onClick = { onSessionProgressResponseLimitChange(null) },
                        enabled = !isRootRunning && !isIntercarrierRunning,
                        label = { Text("Carrier default") }
                    )
                    (1..6).forEach { responseCount ->
                        FilterChip(
                            selected = sessionProgressResponseLimit == responseCount,
                            onClick = { onSessionProgressResponseLimitChange(responseCount) },
                            enabled = !isRootRunning && !isIntercarrierRunning,
                            label = { Text(responseCount.toString()) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Auto restart probe", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = autoRestartProbe, onCheckedChange = onAutoRestartProbeChange)
                }

                Text("Minimum interval between INVITEs", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "0s sends the next INVITE as soon as the previous transaction is terminated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = probeIntervalSeconds == 0,
                        onClick = { onProbeIntervalSecondsChange(0) },
                        enabled = !isRootRunning && !isIntercarrierRunning,
                        label = { Text("0s") }
                    )
                    listOf(5, 10, 20).forEach { intervalSeconds ->
                        FilterChip(
                            selected = probeIntervalSeconds == intervalSeconds,
                            onClick = { onProbeIntervalSecondsChange(intervalSeconds) },
                            enabled = !isRootRunning && !isIntercarrierRunning,
                            label = { Text("${intervalSeconds}s") }
                        )
                    }
                    FilterChip(
                        selected = probeIntervalSeconds == 30,
                        onClick = { onProbeIntervalSecondsChange(30) },
                        enabled = !isRootRunning && !isIntercarrierRunning,
                        label = { Text("30s") }
                    )
                    FilterChip(
                        selected = probeIntervalSeconds == 60,
                        onClick = { onProbeIntervalSecondsChange(60) },
                        enabled = !isRootRunning && !isIntercarrierRunning,
                        label = { Text("60s") }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartProbe,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = !isRootRunning && !isIntercarrierRunning
                    ) { Text("Probe") }

                    Button(
                        onClick = onStopProbe,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = isRootRunning
                    ) { Text("Stop") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartIntercarrierTest,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = !isRootRunning && !isIntercarrierRunning
                    ) { Text("Inter-carrier test", style = MaterialTheme.typography.labelSmall) }

                    Button(
                        onClick = onStopIntercarrierTest,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = isIntercarrierRunning
                    ) { Text("Stop inter-carrier", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Location", style = MaterialTheme.typography.titleMedium)
                val loc = cellLocation
                if (loc != null) {
                    Text(
                        buildString {
                            append("lat=${loc.lat}, lon=${loc.lon}")
                            formatDisplayedAccuracyMeters(loc.range)?.let {
                                append(" · accuracy=$it")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "No location yet. Run Probe or use manual lookup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(MaterialTheme.shapes.medium)
                ) {
                    CellMapView(
                        lat = loc?.lat,
                        lon = loc?.lon,
                        accuracy = loc?.range,
                        mode = cellMapMode,
                        recentProbePoints = recentProbePoints
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CellMapMode.values().forEachIndexed { index, mode ->
                        FilterChip(
                            selected = mode == cellMapMode,
                            onClick = { onCellMapModeChange(mode) },
                            label = { Text("$index ${mode.label}") }
                        )
                    }
                }
                if (cellMapMode == CellMapMode.AllHistory) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Probe timeline", style = MaterialTheme.typography.labelLarge)
                        if (allHistoryTimelineItems.isEmpty()) {
                            Text(
                                "No mappable probe history.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            allHistoryTimelineItems.forEach { item ->
                                Text(
                                    formatTimelineItem(item),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimelineItem(item: CellMapTimelineItem): String {
    val time = MAP_PROBE_TIME_FORMATTER.format(Instant.ofEpochMilli(item.timestampMillis))
    return when (item.type) {
        CellMapTimelineItemType.ProbeStart -> "$time · Probe start"
        CellMapTimelineItemType.ProbeStop -> buildString {
            append("$time · Probe stop")
            item.exitCode?.let { append(" · exit=$it") }
            if (item.stoppedByUser == true) append(" · user stopped")
        }
        CellMapTimelineItemType.ProbePoint -> buildString {
            append(time)
            append(" · lat=${item.lat}, lon=${item.lon}")
            append(" · accuracy=${item.accuracy?.let { "$it m" } ?: "N/A"}")
        }
    }
}

private fun formatDisplayedAccuracyMeters(accuracyMeters: Double?): String? {
    val scaled = accuracyMeters?.takeIf { it > 0 }?.times(DISPLAYED_ACCURACY_SHRINK_FACTOR) ?: return null
    val rounded = (scaled * 10.0).roundToInt() / 10.0
    return String.format(Locale.US, "%.1f m", rounded)
}
