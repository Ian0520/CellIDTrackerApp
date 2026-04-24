package com.example.cellidtracker.ui.tabs

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun LogTabContent(
    scrollState: ScrollState,
    showLog: Boolean,
    onToggleShowLog: () -> Unit,
    output: String,
    preview: String
) {
    val logScrollState = rememberScrollState()
    var followLatest by remember { mutableStateOf(true) }
    val isAtBottom by remember(logScrollState) {
        derivedStateOf { logScrollState.maxValue - logScrollState.value <= 24 }
    }

    LaunchedEffect(showLog) {
        if (showLog) {
            followLatest = true
        }
    }

    LaunchedEffect(showLog, output, followLatest) {
        if (showLog && followLatest) {
            logScrollState.scrollTo(logScrollState.maxValue)
        }
    }

    LaunchedEffect(showLog, logScrollState.isScrollInProgress, logScrollState.value, logScrollState.maxValue) {
        if (showLog && logScrollState.isScrollInProgress && !isAtBottom) {
            followLatest = false
        }
    }

    LaunchedEffect(showLog, isAtBottom) {
        if (showLog && !followLatest && isAtBottom) {
            followLatest = true
        }
    }

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
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Log", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onToggleShowLog) {
                        Text(if (showLog) "Hide" else "Show")
                    }
                }
                if (showLog) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 280.dp, max = 560.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = MaterialTheme.shapes.small
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .padding(bottom = 42.dp)
                                .verticalScroll(logScrollState)
                        ) {
                            Text(
                                text = output,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        TextButton(
                            onClick = { followLatest = true },
                            enabled = !followLatest || !isAtBottom,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                        ) {
                            Text(if (followLatest && isAtBottom) "Following latest" else "Jump to latest")
                        }
                    }
                } else {
                    Text(
                        preview,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
