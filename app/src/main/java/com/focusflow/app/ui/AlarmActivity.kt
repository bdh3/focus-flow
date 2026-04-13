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
import androidx.compose.material.icons.filled.Notifications
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

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // [v1.7.3] 윈도우 플래그 통합 설정 함수 호출 (DeskClock 방식 최적화)
        applyWindowFlags()
        
        super.onCreate(savedInstanceState)
        
        val notificationHelper = NotificationHelper.getInstance(this)
        val taskTitle = intent.getStringExtra("taskTitle") ?: "독립 세션"
        val message = intent.getStringExtra("message") ?: "구간이 전환되었습니다."
        val isFinished = intent.getBooleanExtra("isFinished", false)

        // [v1.7.6-patch] 최신 안드로이드(Z플립5 등)에서 잠긴 화면 깨우기 강화
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // [v1.7.6-patch] 최신 안드로이드(Z플립5 등)에서 잠긴 화면 깨우기 강화
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContent {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
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
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF001B3D) // 깊은 네이비 (001B3D)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 상단 앱 브랜딩 (이 앱에서 보낸 알람임을 명시)
            Column(
                modifier = Modifier.padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = Color(0xFF6CDBAC), // 좀 더 편안한 민트/그린색으로 변경
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "FOCUS FLOW",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.6f),
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 중앙 알람 메시지
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )
            }

            // 큰 중단 버튼 (갤럭시 알람 스타일)
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .size(120.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "알람 중단",
                        modifier = Modifier.size(40.dp)
                    )
                    Text("중단", fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
