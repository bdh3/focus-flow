package com.focusflow.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.focusflow.app.service.TimerService
import com.focusflow.app.util.NotificationHelper

import com.focusflow.app.ui.theme.FocusFlowTheme
import androidx.compose.foundation.isSystemInDarkTheme

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // [v1.7.6-fix] Z플립5 커버 스크린 하드웨어를 즉각적으로 깨우기 위한 설정
        // super.onCreate 이전에 호출하여 시스템 레이어에 즉시 반영
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        
        // Android 10(Q) 이상에서 잠금 화면 위 표시 및 커버 스크린 가시성 보장
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setInheritShowWhenLocked(true)
        }

        applyWindowFlags()
        super.onCreate(savedInstanceState)
        
        val notificationHelper = NotificationHelper.getInstance(this)
        val taskTitle = intent.getStringExtra("taskTitle") ?: "집중 세션"
        val message = intent.getStringExtra("message") ?: "구간이 전환되었습니다."
        val isFinished = intent.getBooleanExtra("isFinished", false)

        setContent {
            val isDark = isSystemInDarkTheme()
            FocusFlowTheme(darkTheme = isDark, dynamicColor = false) {
                AlarmScreen(
                    taskTitle = taskTitle,
                    message = message,
                    isFinished = isFinished,
                    onDismiss = {
                        notificationHelper.stopAllAlerts()
                        val stopIntent = Intent(this@AlarmActivity, TimerService::class.java).apply {
                            putExtra("stop_alarm", true)
                            putExtra("isFinished", isFinished)
                        }
                        startService(stopIntent)
                        finish()
                    }
                )
            }
        }

        lifecycleScope.launch {
            delay(NotificationHelper.ALARM_TIMEOUT_MS)
            if (!isDestroyed) {
                // [v1.7.4-fix] 타임아웃 시 소리/진동을 명시적으로 끄고 액티비티 종료
                notificationHelper.stopAllAlerts()
                finish()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyWindowFlags()
    }

    private fun applyWindowFlags() {
        // [v1.7.6-final] 패턴 해제를 요청하지 않고, 잠금 화면 위에 화면만 띄움
        // requestDismissKeyguard를 호출하면 오히려 패턴 입력창이 소환되므로 제거함
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setInheritShowWhenLocked(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // [v1.7.3] onDestroy에서 무조건 소리를 끄면 액티비티 생명주기 변화에 따라 음악이 끊김.
        // 소리 정지는 오직 유저의 '중단' 클릭이나 20초 타임아웃에 의해서만 수행되도록 변경.
    }
}

@Composable
fun AlarmScreen(
    taskTitle: String,
    message: String,
    isFinished: Boolean = false,
    onDismiss: () -> Unit
) {
    // [v1.8.0] 시스템 테마와 독립적인 "Midnight Blue" 고정 디자인 적용
    // 낮과 밤 모두 눈이 편안하고 브랜드 정체성(Blue/Yellow)을 가장 잘 드러냄
    val backgroundColor = Color(0xFF0F172A) // Slate 900 (Deep Navy)
    val textColor = Color.White
    val subTextColor = Color.White.copy(alpha = 0.7f)
    val primaryBrandColor = Color(0xFF38BDF8) // Sky Blue
    val tertiaryBrandColor = Color(0xFFFACC15) // Yellow

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Text(
                text = "FOCUS FLOW",
                style = MaterialTheme.typography.labelLarge,
                color = primaryBrandColor,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 24.dp)
            )

            // Central Status Icon
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val isRest = message.contains("휴식") || message.contains("REST")
                val statusColor = if (isRest) tertiaryBrandColor else primaryBrandColor
                
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(140.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isRest) Icons.Default.Coffee else Icons.Default.Timer,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = (-1).sp
                    ),
                    color = textColor,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    color = subTextColor,
                    textAlign = TextAlign.Center
                )
            }

            // Dismiss Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onDismiss,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444), // Red 500
                        contentColor = Color.White
                    ),
                    modifier = Modifier.size(80.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "중단",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }
}
