package com.example.adhdblockscheduler.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.adhdblockscheduler.ui.viewmodel.SchedulerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SchedulerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    var alarmInterval by remember { mutableIntStateOf(uiState.alarmIntervalMinutes) }
    var vibrationEnabled by remember { mutableStateOf(uiState.vibrationEnabled) }
    var calendarSyncEnabled by remember { mutableStateOf(uiState.calendarSyncEnabled) }

    // 초기값 동기화 (한 번만)
    LaunchedEffect(uiState.alarmIntervalMinutes) {
        alarmInterval = uiState.alarmIntervalMinutes
    }
    LaunchedEffect(uiState.vibrationEnabled) {
        vibrationEnabled = uiState.vibrationEnabled
    }
    LaunchedEffect(uiState.calendarSyncEnabled) {
        calendarSyncEnabled = uiState.calendarSyncEnabled
    }

    Scaffold(
        topBar = {
            val isModified = uiState.alarmIntervalMinutes != alarmInterval || 
                             uiState.vibrationEnabled != vibrationEnabled || 
                             uiState.calendarSyncEnabled != calendarSyncEnabled
            
            TopAppBar(
                title = { Text("설정") },
                actions = {
                    Button(
                        onClick = {
                            viewModel.saveSettings(alarmInterval, vibrationEnabled, calendarSyncEnabled)
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        enabled = isModified
                    ) {
                        Text(if (isModified) "저장" else "저장됨")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("알림 설정", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            ListItem(
                headlineContent = { Text("알림 단위") },
                supportingContent = { Text("작업 중 ${alarmInterval}분마다 알림을 줍니다.") },
                trailingContent = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${alarmInterval}분")
                        Slider(
                            value = when(alarmInterval) {
                                5 -> 0f
                                15 -> 1f
                                30 -> 2f
                                else -> 1f
                            },
                            onValueChange = { 
                                alarmInterval = when(it.toInt()) {
                                    0 -> 5
                                    1 -> 15
                                    2 -> 30
                                    else -> 15
                                }
                            },
                            valueRange = 0f..2f,
                            steps = 1,
                            modifier = Modifier.width(120.dp)
                        )
                    }
                }
            )

            ListItem(
                headlineContent = { Text("진동 알림") },
                supportingContent = { Text("알림 발생 시 진동을 켭니다.") },
                trailingContent = {
                    Switch(
                        checked = vibrationEnabled,
                        onCheckedChange = { 
                            vibrationEnabled = it
                            if (it) viewModel.updateVibration(true)
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "연동 설정", 
                style = MaterialTheme.typography.titleMedium, 
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            ListItem(
                headlineContent = { Text("캘린더 자동 기록") },
                supportingContent = { Text("집중 블록 완료 시 삼성/구글 캘린더에 기록합니다.") },
                trailingContent = {
                    Switch(
                        checked = calendarSyncEnabled,
                        onCheckedChange = { calendarSyncEnabled = it }
                    )
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "버전 1.1.6",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}
