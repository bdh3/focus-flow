package com.focusflow.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.focusflow.app.BuildConfig
import com.focusflow.app.ui.viewmodel.SchedulerViewModel
import com.focusflow.app.util.VibrationPattern
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SchedulerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    var currentPickingType by remember { mutableStateOf("focus") }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            when (currentPickingType) {
                "focus" -> viewModel.setFocusRingtoneUri(uri?.toString())
                "rest" -> viewModel.setRestRingtoneUri(uri?.toString())
                "finish" -> viewModel.setFinishRingtoneUri(uri?.toString())
            }
        }
    }

    fun getRingtoneName(uriStr: String?): String {
        if (uriStr == null) return "시스템 기본 벨소리"
        return try {
            val uri = uriStr.toUri()
            RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "시스템 기본 벨소리"
        } catch (e: Exception) {
            "시스템 기본 벨소리"
        }
    }

    val focusRingtoneName = remember(uiState.focusRingtoneUri) { getRingtoneName(uiState.focusRingtoneUri) }
    val restRingtoneName = remember(uiState.restRingtoneUri) { getRingtoneName(uiState.restRingtoneUri) }
    val finishRingtoneName = remember(uiState.finishRingtoneUri) { getRingtoneName(uiState.finishRingtoneUri) }
    
    // 화면을 벗어날 때 소리 미리보기를 중단합니다.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopSoundPreview()
        }
    }
    var alarmInterval by remember { mutableIntStateOf(uiState.storedAlarmIntervalMinutes) }
    var restMinutes by remember { mutableIntStateOf(uiState.storedRestMinutes) }
    var defaultTotalMinutes by remember { mutableIntStateOf(uiState.defaultTotalMinutes) }
    var vibrationEnabled by remember { mutableStateOf(uiState.vibrationEnabled) }
    var soundEnabled by remember { mutableStateOf(uiState.soundEnabled) }
    var focusPatternId by remember { mutableStateOf(uiState.focusVibrationPatternId) }
    var restPatternId by remember { mutableStateOf(uiState.restVibrationPatternId) }
    var finishPatternId by remember { mutableStateOf(uiState.finishVibrationPatternId) }
    var focusSoundId by remember { mutableStateOf(uiState.focusSoundId) }
    var restSoundId by remember { mutableStateOf(uiState.restSoundId) }
    var finishSoundId by remember { mutableStateOf(uiState.finishSoundId) }
    var useFullScreenAlarm by remember { mutableStateOf(uiState.useFullScreenAlarm) }
    var darkMode by remember { mutableIntStateOf(uiState.darkMode) }
    var fontSizeScale by remember { mutableFloatStateOf(uiState.fontSizeScale) }

    // 저장된 설정값들이 외부(DB)에서 변경되었을 때만 로컬 편집 상태를 동기화합니다.
    LaunchedEffect(uiState.storedAlarmIntervalMinutes, uiState.storedRestMinutes, uiState.defaultTotalMinutes) {
        alarmInterval = uiState.storedAlarmIntervalMinutes
        restMinutes = uiState.storedRestMinutes
        defaultTotalMinutes = uiState.defaultTotalMinutes
    }

    LaunchedEffect(uiState.vibrationEnabled, uiState.soundEnabled, uiState.focusVibrationPatternId, uiState.restVibrationPatternId, uiState.finishVibrationPatternId, uiState.focusSoundId, uiState.restSoundId, uiState.finishSoundId, uiState.useFullScreenAlarm, uiState.darkMode, uiState.fontSizeScale) {
        vibrationEnabled = uiState.vibrationEnabled
        soundEnabled = uiState.soundEnabled
        focusPatternId = uiState.focusVibrationPatternId
        restPatternId = uiState.restVibrationPatternId
        finishPatternId = uiState.finishVibrationPatternId
        focusSoundId = uiState.focusSoundId
        restSoundId = uiState.restSoundId
        finishSoundId = uiState.finishSoundId
        useFullScreenAlarm = uiState.useFullScreenAlarm
        darkMode = uiState.darkMode
        fontSizeScale = uiState.fontSizeScale
    }

    val divisorsOf60 = listOf(1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60)

    fun getValidRestFor60(focus: Int, currentRest: Int): Int {
        val possibleSums = divisorsOf60.filter { it > focus }
        if (possibleSums.isEmpty()) return 60 - focus
        return possibleSums.minByOrNull { abs((it - focus) - currentRest) }?.let { it - focus } ?: (60 - focus)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                actions = {
                    val isModified = (uiState.storedAlarmIntervalMinutes != alarmInterval) || 
                                     (uiState.storedRestMinutes != restMinutes) ||
                                     (uiState.defaultTotalMinutes != defaultTotalMinutes) ||
                                     (uiState.vibrationEnabled != vibrationEnabled) ||
                                     (uiState.soundEnabled != soundEnabled) ||
                                     (uiState.focusVibrationPatternId != focusPatternId) ||
                                     (uiState.restVibrationPatternId != restPatternId) ||
                                     (uiState.finishVibrationPatternId != finishPatternId) ||
                                     (uiState.focusSoundId != focusSoundId) ||
                                     (uiState.restSoundId != restSoundId) ||
                                     (uiState.finishSoundId != finishSoundId) ||
                                     (uiState.useFullScreenAlarm != useFullScreenAlarm) ||
                                     (uiState.darkMode != darkMode) ||
                                     (uiState.fontSizeScale != fontSizeScale)

                    val canSave = isModified && !uiState.isTimerActive

                    Button(
                        onClick = {
                            viewModel.saveSettings(
                                alarmInterval, restMinutes, vibrationEnabled, soundEnabled, 
                                uiState.calendarSyncEnabled, focusPatternId, restPatternId, finishPatternId, 
                                focusSoundId, restSoundId, finishSoundId, defaultTotalMinutes, darkMode,
                                uiState.focusRingtoneUri, uiState.restRingtoneUri, uiState.finishRingtoneUri,
                                useFullScreenAlarm
                            )
                            viewModel.setFontSizeScale(fontSizeScale)
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        enabled = canSave
                    ) {
                        Text(
                            when {
                                uiState.isTimerActive -> "진행 중 수정 불가"
                                isModified -> "저장"
                                else -> "저장됨"
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🎯 몰입 모드 기본값",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. 모드 선택 (탭 스타일)
                    var isCycleMode by remember { mutableStateOf(restMinutes > 0) }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("집중 방식", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier
                                .width(200.dp)
                                .height(36.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(if (!isCycleMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { 
                                        isCycleMode = false
                                        alarmInterval = 15
                                        restMinutes = 0
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "연속", 
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (!isCycleMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(if (isCycleMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { 
                                        isCycleMode = true
                                        if (restMinutes == 0) {
                                            alarmInterval = 25
                                            restMinutes = 5
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "사이클", 
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isCycleMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. 상세 설정 영역
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (!isCycleMode) {
                                // 연속 집중 모드
                                Text("알람 주기 (일정 시간마다 리마인드)", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(5, 10, 15, 30, 60).forEach { interval ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .background(if (alarmInterval == interval) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                                .clickable { alarmInterval = interval },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                if (interval >= 60) "${interval / 60}시간" else "${interval}분",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (alarmInterval == interval) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 인터벌 사이클 모드
                                Text("빠른 프리셋", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                    AssistChip(
                                        onClick = { 
                                            alarmInterval = 25
                                            restMinutes = 5
                                        },
                                        label = { Text("25/5", style = MaterialTheme.typography.labelMedium) },
                                        leadingIcon = { Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                    AssistChip(
                                        onClick = { 
                                            alarmInterval = 50
                                            restMinutes = 10
                                        },
                                        label = { Text("50/10", style = MaterialTheme.typography.labelMedium) },
                                        leadingIcon = { Icon(imageVector = Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                
                                Text("상세 값 수정", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(8.dp))
                                
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("집중: ${alarmInterval}분", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                                        Slider(
                                            value = alarmInterval.toFloat(),
                                            onValueChange = { 
                                                alarmInterval = (it.toInt() / 5) * 5
                                                restMinutes = getValidRestFor60(alarmInterval, restMinutes)
                                            },
                                            valueRange = 5f..55f,
                                            modifier = Modifier.weight(1f),
                                            steps = 9 // 5, 10, 15, ..., 55
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("휴식: ${restMinutes}분", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                                        Slider(
                                            value = restMinutes.toFloat(),
                                            onValueChange = { 
                                                val requestedRest = it.toInt().coerceAtLeast(1)
                                                val targetSum = divisorsOf60.filter { d -> d > alarmInterval }
                                                    .minByOrNull { d -> abs((d - alarmInterval) - requestedRest) } ?: 60
                                                restMinutes = targetSum - alarmInterval
                                            },
                                            valueRange = 1f..(60 - alarmInterval).toFloat(),
                                            modifier = Modifier.weight(1f),
                                            steps = (60 - alarmInterval - 1).coerceAtLeast(0)
                                        )
                                    }
                                    Text(
                                        "* 60분의 약수에 맞춰 자동 보정됩니다.", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                            // 바로 몰입 총 시간 설정 추가
                            Text("총 세션 시간 (바로 몰입 시작 시)", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(8.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val hours = defaultTotalMinutes / 60
                                val mins = defaultTotalMinutes % 60
                                val timeText = "${hours}시간 ${mins}분"
                                
                                Text(timeText, modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
                                Slider(
                                    value = defaultTotalMinutes.toFloat(),
                                    onValueChange = { 
                                        // 30분 단위로 조정
                                        defaultTotalMinutes = (it.toInt() / 30) * 30
                                    },
                                    valueRange = 30f..480f, // 30분 ~ 8시간(480분)
                                    modifier = Modifier.weight(1f),
                                    steps = 14 // (480-30)/30 - 1 = 14 steps
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("알림 및 시스템", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            ListItem(
                headlineContent = { Text("소리 알림") },
                supportingContent = { Text("구간 전환 시 선택한 알림음을 재생합니다.") },
                trailingContent = {
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = { 
                            soundEnabled = it
                            // viewModel.setSoundEnabled(it) // 즉시 저장 제거
                        },
                        enabled = !uiState.isTimerActive // 타이머 작동 중 비활성화
                    )
                }
            )

            if (soundEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        SoundPatternSelector(
                            label = "집중 시작",
                            selectedId = focusSoundId,
                            ringtoneName = focusRingtoneName,
                            onSelected = { 
                                focusSoundId = it
                                viewModel.setFocusSound(it)
                                // 벨소리가 아닐 때만 미리듣기 재생 (벨소리는 피커에서 자체 재생됨)
                                if (it != "ringtone") {
                                    viewModel.previewSound(it, "focus")
                                }
                            },
                            onRingtoneClick = {
                                currentPickingType = "focus"
                                viewModel.stopSoundPreview()
                                launchRingtonePicker(ringtonePickerLauncher, uiState.focusRingtoneUri)
                            },
                            onStopPreview = { viewModel.stopSoundPreview() }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SoundPatternSelector(
                            label = "휴식 시작",
                            selectedId = restSoundId,
                            ringtoneName = restRingtoneName,
                            onSelected = { 
                                restSoundId = it
                                viewModel.setRestSound(it)
                                // 벨소리가 아닐 때만 미리듣기 재생
                                if (it != "ringtone") {
                                    viewModel.previewSound(it, "rest")
                                }
                            },
                            onRingtoneClick = {
                                currentPickingType = "rest"
                                viewModel.stopSoundPreview()
                                launchRingtonePicker(ringtonePickerLauncher, uiState.restRingtoneUri)
                            },
                            onStopPreview = { viewModel.stopSoundPreview() }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SoundPatternSelector(
                            label = "전체 종료",
                            selectedId = finishSoundId,
                            ringtoneName = finishRingtoneName,
                            onSelected = { 
                                finishSoundId = it
                                viewModel.setFinishSound(it)
                                // 벨소리가 아닐 때만 미리듣기 재생
                                if (it != "ringtone") {
                                    viewModel.previewSound(it, "finish")
                                }
                            },
                            onRingtoneClick = {
                                currentPickingType = "finish"
                                viewModel.stopSoundPreview()
                                launchRingtonePicker(ringtonePickerLauncher, uiState.finishRingtoneUri)
                            },
                            onStopPreview = { viewModel.stopSoundPreview() }
                        )
                    }
                }
            }

            ListItem(
                headlineContent = { Text("진동 알림") },
                supportingContent = { Text("구간 전환 시 진동을 켭니다.") },
                trailingContent = {
                    Switch(
                        checked = vibrationEnabled,
                        onCheckedChange = { 
                            vibrationEnabled = it
                            // viewModel.setVibrationEnabled(it) // 즉시 저장 제거
                        },
                        enabled = !uiState.isTimerActive
                    )
                }
            )

            if (vibrationEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        VibrationPatternSelector(
                            label = "집중 시작",
                            selectedId = focusPatternId,
                            onSelected = { 
                                focusPatternId = it
                                viewModel.setFocusVibrationPattern(it)
                                viewModel.previewVibration(it)
                            },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        VibrationPatternSelector(
                            label = "휴식 시작",
                            selectedId = restPatternId,
                            onSelected = { 
                                restPatternId = it
                                viewModel.setRestVibrationPattern(it)
                                viewModel.previewVibration(it)
                            },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        VibrationPatternSelector(
                            label = "전체 종료",
                            selectedId = finishPatternId,
                            onSelected = { 
                                finishPatternId = it
                                viewModel.setFinishVibrationPattern(it)
                                viewModel.previewVibration(it)
                            },
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("알람 표시 방식", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(4.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (useFullScreenAlarm) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable(enabled = !uiState.isTimerActive) { useFullScreenAlarm = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "전체 화면 알람",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (useFullScreenAlarm) MaterialTheme.colorScheme.onPrimary 
                                    else if (uiState.isTimerActive) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (!useFullScreenAlarm) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable(enabled = !uiState.isTimerActive) { useFullScreenAlarm = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "상단 팝업 알람",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (!useFullScreenAlarm) MaterialTheme.colorScheme.onPrimary 
                                    else if (uiState.isTimerActive) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = if (useFullScreenAlarm) 
                    "• 화면 전체를 점유하며 상세 메시지를 함께 표시합니다.\n• 벨소리, 알림음, 진동 모두 지원합니다." 
                    else 
                    "• 화면 상단에 간결하게 표시됩니다.\n• 벨소리 설정 시에는 수동 중지를 위해 전체 화면으로 전환됩니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                lineHeight = 16.sp
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("시스템 설정", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "다크 모드",
                    style = MaterialTheme.typography.bodyLarge
                )
                
                Row(
                    modifier = Modifier
                        .width(200.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(2.dp)
                ) {
                    listOf("시스템", "라이트", "다크").forEachIndexed { index, label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(if (darkMode == index) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable { 
                                    darkMode = index
                                    viewModel.setDarkMode(index)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (darkMode == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "글자 크기",
                    style = MaterialTheme.typography.bodyLarge
                )
                
                Row(
                    modifier = Modifier
                        .width(200.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(2.dp)
                ) {
                    val scales = listOf(0.85f, 1.0f, 1.25f)
                    val labels = listOf("작게", "중간", "크게")
                    scales.forEachIndexed { index, scale ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(if (fontSizeScale == scale) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable {
                                    fontSizeScale = scale
                                    viewModel.setFontSizeScale(scale)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                labels[index],
                                style = MaterialTheme.typography.labelMedium,
                                color = if (fontSizeScale == scale) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

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

            val canDrawOverlays = viewModel.canDrawOverlays()

            ListItem(
                headlineContent = { Text("다른 앱 위에 표시") },
                supportingContent = {
                    Text(if (canDrawOverlays)
                        "잠금 화면이나 다른 앱 사용 중에도 알람 화면을 즉시 띄울 수 있습니다."
                        else "잠금 화면을 뚫고 알람을 표시하기 위해 이 권한이 필수적입니다.")
                },
                trailingContent = {
                    Button(
                        onClick = { viewModel.requestDrawOverlaysPermission() },
                        enabled = !canDrawOverlays,
                        colors = if (canDrawOverlays)
                            ButtonDefaults.filledTonalButtonColors()
                            else ButtonDefaults.buttonColors()
                    ) {
                        Text(if (canDrawOverlays) "설정됨" else "설정하기")
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 24.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun SoundPatternSelector(
    label: String,
    selectedId: String,
    ringtoneName: String,
    onSelected: (String) -> Unit,
    onRingtoneClick: () -> Unit,
    onStopPreview: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val patterns = com.focusflow.app.util.SoundPattern.getAllPatterns()
    val currentPattern = patterns.find { it.id == selectedId } ?: patterns.first()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectedId == "ringtone") ringtoneName else currentPattern.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                color = if (selectedId == "ringtone") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            patterns.forEach { pattern ->
                DropdownMenuItem(
                    text = { 
                        Text(
                            text = if (pattern.id == "ringtone") "벨소리: $ringtoneName" else pattern.displayName,
                            style = MaterialTheme.typography.bodyMedium
                        ) 
                    },
                    onClick = {
                        if (pattern.id == "ringtone") {
                            // 벨소리를 선택했거나, 이미 벨소리인 상태에서 다시 누른 경우
                            // 미리듣기를 재생하지 않고(중복 방지) 바로 피커만 띄움
                            onRingtoneClick()
                            onSelected(pattern.id)
                        } else {
                            // 일반 효과음을 선택한 경우 기존 소리 중단 후 선택 및 미리듣기 재생
                            onStopPreview()
                            onSelected(pattern.id)
                        }
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun VibrationPatternSelector(
    label: String,
    selectedId: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val patterns = VibrationPattern.getAllPatterns()
    val currentPattern = patterns.find { it.id == selectedId } ?: patterns.first()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentPattern.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            patterns.forEach { pattern ->
                DropdownMenuItem(
                    text = { Text(pattern.displayName, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelected(pattern.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun RingtonePickerButton(
    label: String,
    ringtoneName: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("$label: $ringtoneName", style = MaterialTheme.typography.bodyMedium)
    }
}

fun launchRingtonePicker(launcher: androidx.activity.result.ActivityResultLauncher<Intent>, existingUri: String?) {
    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "벨소리 선택")
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri?.toUri())
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
    }
    launcher.launch(intent)
}
