package com.focusflow.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.focusflow.app.FocusFlowApplication
import com.focusflow.app.service.TimerService
import com.focusflow.app.ui.screen.MainScreen
import com.focusflow.app.ui.screen.Screen
import com.focusflow.app.ui.screen.SplashScreen
import com.focusflow.app.ui.theme.FocusFlowTheme
import com.focusflow.app.ui.viewmodel.SchedulerViewModel

// Define top-level navigation routes
sealed class RootScreen(val route: String) {
    object Splash : RootScreen("splash")
    object Main : RootScreen("main")
}

class MainActivity : ComponentActivity() {
    private val viewModel: SchedulerViewModel by viewModels {
        val app = application as FocusFlowApplication
        SchedulerViewModel.Factory(
            app = app,
            repository = app.taskRepository,
            settingsRepository = app.settingsRepository,
            statsRepository = app.statsRepository,
            scheduleRepository = app.scheduleRepository
        )
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerService.TimerBinder
            viewModel.setTimerService(binder.getService())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.setTimerService(null)
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // 시스템 스플래시 활성화
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // [v1.7.3] 서비스 바인딩을 onCreate로 이동하여 백그라운드/알람 상태에서도 연결 유지
        Intent(this, TimerService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        handleIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val darkTheme = when (uiState.darkMode) {
                1 -> false
                2 -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            FocusFlowTheme(
                darkTheme = darkTheme,
                fontSizeScale = uiState.fontSizeScale
            ) {
                val context = LocalContext.current
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    // 알림 권한 요청 (Android 13+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    // 배터리 최적화 제외 요청 (타이머 정확도 및 백그라운드 알림 유지)
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }

                    // [v1.7.3] '다른 앱 위에 표시' 권한 확인 (잠금 화면 알람 필수) - 초기 실행 시 강제 팝업 방지를 위해 로직 수정 가능하나 현재는 설정 유도
                    /*
                    if (!Settings.canDrawOverlays(context)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                    */
                }
                
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = RootScreen.Splash.route) {
                        composable(RootScreen.Splash.route) {
                            SplashScreen(onTimeout = {
                                navController.navigate(RootScreen.Main.route) {
                                    popUpTo(RootScreen.Splash.route) { inclusive = true }
                                }
                            })
                        }
                        composable(RootScreen.Main.route) {
                            MainScreen(viewModel = viewModel, rootNavController = navController)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // 새로운 인텐트로 교체

        handleIntent(intent)

        val route = intent.getStringExtra("navigate_to")
        if (route != null) {
            // Deep link handling: navigate to MainScreen and then to the specific tab
            // This assumes MainScreen's internal NavHost can handle the route
            setContent {
                val uiState by viewModel.uiState.collectAsState()
                val darkTheme = when (uiState.darkMode) {
                    1 -> false
                    2 -> true
                    else -> isSystemInDarkTheme()
                }

                FocusFlowTheme(
                darkTheme = darkTheme,
                fontSizeScale = uiState.fontSizeScale
            ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        NavHost(navController = navController, startDestination = RootScreen.Main.route) {
                            composable(RootScreen.Main.route) {
                                MainScreen(viewModel = viewModel, rootNavController = navController, startRoute = route)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // [정책 5] 앱으로 진입하면 현재 울리는 벨소리/알람을 즉시 중단
        val helper = com.focusflow.app.util.NotificationHelper.getInstance(this)
        helper.stopAllAlerts()
        
        // [v1.7.6-patch] 앱 재진입 시 타이머가 돌고 있지 않다면 선택된 작업 초기화 ("작업을 선택하세요" 상태로 유도)
        if (!viewModel.uiState.value.isTimerActive) {
            viewModel.selectTask(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // [v1.7.3] 앱 종료 시 바인딩 해제
        try {
            unbindService(connection)
        } catch (e: Exception) {}
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra("stop_alarm", false)) {
            val serviceIntent = Intent(this, com.focusflow.app.service.TimerService::class.java).apply {
                putExtra("stop_alarm", true)
            }
            startService(serviceIntent)
        }
    }
}
