package com.example.adhdblockscheduler.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.adhdblockscheduler.BuildConfig
import com.example.adhdblockscheduler.ui.viewmodel.SchedulerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SchedulerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    var alarmInterval by remember { mutableIntStateOf(uiState.alarmIntervalMinutes) }
    var vibrationEnabled by remember { mutableStateOf(uiState.vibrationEnabled) }

    // 초기값 동기화 (한 번만)
    LaunchedEffect(uiState.alarmIntervalMinutes) {
        alarmInterval = uiState.alarmIntervalMinutes
    }
    LaunchedEffect(uiState.vibrationEnabled) {
        vibrationEnabled = uiState.vibrationEnabled
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                actions = {
                    val isModified = (uiState.alarmIntervalMinutes != alarmInterval) || 
                                     (uiState.vibrationEnabled != vibrationEnabled)

                    Button(
                        onClick = {
                            viewModel.saveSettings(alarmInterval, vibrationEnabled, false)
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
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("시스템 설정", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            val isIgnoringBattery = viewModel.isIgnoringBatteryOptimizations()
            
            ListItem(
                headlineContent = { Text("배터리 최적화 제외") },
                supportingContent = { 
                    Text(if (isIgnoringBattery) 
                        "백그라운드에서 정확한 알람을 위해 배터리 제한이 해제된 상태입니다." 
                        else "화면이 꺼졌을 때 알람이 누락되는 것을 방지하기 위해 이 설정이 권장됩니다.") 
                },
                trailingContent = {
                    Button(
                        onClick = { viewModel.requestIgnoreBatteryOptimizations() },
                        enabled = !isIgnoringBattery,
                        colors = if (isIgnoringBattery) 
                            ButtonDefaults.filledTonalButtonColors() 
                            else ButtonDefaults.buttonColors()
                    ) {
                        Text(if (isIgnoringBattery) "설정됨" else "설정하기")
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "버전 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}
