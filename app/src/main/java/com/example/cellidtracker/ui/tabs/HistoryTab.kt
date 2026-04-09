package com.example.cellidtracker.ui.tabs

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cellidtracker.history.ProbeHistory
import com.example.cellidtracker.history.decodeTowers
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun HistoryTabContent(
    scrollState: ScrollState,
    victimTabs: List<String>,
    currentVictimTab: String?,
    list: List<ProbeHistory>,
    timeFormatter: DateTimeFormatter,
    onVictimTabSelected: (String) -> Unit,
    onClearCurrentVictim: () -> Unit,
    onExportAll: () -> Unit,
    onHistoryItemSelected: (ProbeHistory) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(scrollState)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("History", style = MaterialTheme.typography.titleMedium)

                if (victimTabs.isEmpty()) {
                    Text(
                        "No history yet. Run Probe to collect data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    TabRow(
                        selectedTabIndex = victimTabs.indexOf(currentVictimTab),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        victimTabs.forEach { victim ->
                            Tab(
                                selected = victim == currentVictimTab,
                                onClick = { onVictimTabSelected(victim) },
                                text = { Text(victim.ifBlank { "(unknown)" }) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onClearCurrentVictim,
                            enabled = currentVictimTab != null
                        ) {
                            Text("Clear this victim")
                        }
                        TextButton(onClick = onExportAll) {
                            Text("Export all to file")
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 320.dp)
                    ) {
                        itemsIndexed(list) { idx, item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onHistoryItemSelected(item) }
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "MCC=${item.mcc}, MNC=${item.mnc}, LAC=${item.lac}, CID=${item.cid}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Time: ${timeFormatter.format(Instant.ofEpochMilli(item.timestampMillis))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Victim: ${item.victim.ifBlank { "(unknown)" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Moving: ${if (item.moving) "Yes" else "No"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Delta: ${item.deltaMs?.let { "${it} ms" } ?: "N/A"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Towers used: ${item.towersCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val towersStr = decodeTowers(item.towersJson)
                                    .joinToString(limit = 5, separator = "\n") { t ->
                                        "  - mcc=${t.mcc}, mnc=${t.mnc}, lac=${t.lac}, cid=${t.cid}"
                                    }
                                if (towersStr.isNotEmpty()) {
                                    Text(
                                        "Towers:\n$towersStr",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (item.lat != null && item.lon != null) {
                                    Text(
                                        buildString {
                                            append("lat=${item.lat}, lon=${item.lon}")
                                            if (item.accuracy != null) append(" · acc=${item.accuracy} m")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        "Location unavailable",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (idx < list.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
