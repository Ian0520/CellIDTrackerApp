package com.example.cellidtracker

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cellidtracker.ui.tabs.HistoryTabContent
import com.example.cellidtracker.ui.tabs.LogTabContent
import com.example.cellidtracker.ui.tabs.ProbeTabContent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val probeColumnScrollState = rememberScrollState()
                val scrollStateSecondary = rememberScrollState()
                var selectedTab by remember { mutableStateOf(0) }
                val timeFormatter = remember {
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
                }

                DisposableEffect(viewModel.isRootRunning, viewModel.isIntercarrierRunning) {
                    if (viewModel.isRootRunning || viewModel.isIntercarrierRunning) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is MainUiEvent.ShowSnackbar -> {
                                snackbarHostState.showSnackbar(event.message)
                            }

                            MainUiEvent.OpenProbeMap -> {
                                selectedTab = 0
                                withFrameNanos { }
                                probeColumnScrollState.animateScrollTo(probeColumnScrollState.maxValue)
                            }
                        }
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Probe") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Log") }
                            )
                            Tab(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                text = { Text("History") }
                            )
                        }

                        when (selectedTab) {
                            0 -> ProbeTabContent(
                                probeColumnScrollState = probeColumnScrollState,
                                activeExperimentSessionId = viewModel.activeExperimentSessionId,
                                activeExperimentStartedAtMillis = viewModel.activeExperimentStartedAtMillis,
                                onStartExperimentSession = viewModel::startExperimentSession,
                                onStopExperimentSession = viewModel::stopExperimentSession,
                                victimInput = viewModel.victimInput,
                                onVictimInputChange = viewModel::onVictimInputChange,
                                isMoving = viewModel.isMoving,
                                onMovingChange = viewModel::onMovingChange,
                                onSetVictimNumber = viewModel::setVictimNumber,
                                isRootRunning = viewModel.isRootRunning,
                                isIntercarrierRunning = viewModel.isIntercarrierRunning,
                                mccInput = viewModel.mccInput,
                                mncInput = viewModel.mncInput,
                                lacInput = viewModel.lacInput,
                                cidInput = viewModel.cidInput,
                                cellLocation = viewModel.cellLocation,
                                cellMapMode = viewModel.cellMapMode,
                                recentProbePoints = viewModel.mapProbePoints(),
                                allHistoryTimelineItems = viewModel.allHistoryTimelineItems(),
                                onCellMapModeChange = viewModel::onCellMapModeChange,
                                intercarrierStatus = viewModel.intercarrierStatus,
                                onStartProbe = viewModel::startProbe,
                                onStopProbe = viewModel::stopProbe,
                                onStartIntercarrierTest = viewModel::startIntercarrierTest,
                                onStopIntercarrierTest = viewModel::stopIntercarrierTest
                            )

                            1 -> LogTabContent(
                                scrollState = scrollStateSecondary,
                                showLog = viewModel.showLog,
                                onToggleShowLog = viewModel::toggleShowLog,
                                output = viewModel.output,
                                preview = viewModel.logPreview
                            )

                            2 -> {
                                val victimTabs = viewModel.historyByVictim.keys.sorted()
                                val currentVictimTab = viewModel.selectedHistoryVictim
                                    ?.takeIf { viewModel.historyByVictim.containsKey(it) }
                                    ?: victimTabs.firstOrNull()

                                HistoryTabContent(
                                    scrollState = scrollStateSecondary,
                                    victimTabs = victimTabs,
                                    currentVictimTab = currentVictimTab,
                                    list = currentVictimTab?.let { viewModel.historyByVictim[it] } ?: emptyList(),
                                    timeFormatter = timeFormatter,
                                    onVictimTabSelected = viewModel::selectHistoryVictim,
                                    onClearCurrentVictim = viewModel::clearCurrentVictimHistory,
                                    onExportAll = viewModel::exportAllHistory,
                                    onHistoryItemSelected = viewModel::selectHistoryItem
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
