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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.example.cellidtracker.CellMapView
import com.example.cellidtracker.ui.components.SmallInfoChip

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProbeTabContent(
    probeColumnScrollState: ScrollState,
    victimInput: String,
    onVictimInputChange: (String) -> Unit,
    isMoving: Boolean,
    onMovingChange: (Boolean) -> Unit,
    onSetVictimNumber: () -> Unit,
    isRootRunning: Boolean,
    isIntercarrierRunning: Boolean,
    mccInput: String,
    mncInput: String,
    lacInput: String,
    cidInput: String,
    cellLocation: CellLocationResult?,
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
                                    if (loc.range != null) append(" · accuracy=${loc.range} m")
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
                            if (loc.range != null) append(" · accuracy=${loc.range} m")
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
                        accuracy = loc?.range
                    )
                }
            }
        }
    }
}
