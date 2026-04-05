package com.example.adhdblockscheduler.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.adhdblockscheduler.ui.viewmodel.SchedulerViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Calendar : Screen("calendar", "캘린더", Icons.Default.DateRange)
    object Timer : Screen("timer", "타이머", Icons.Default.PlayArrow)
    object Settings : Screen("settings", "설정", Icons.Default.Settings)
}

@Composable
fun MainScreen(viewModel: SchedulerViewModel, startRoute: String? = null) {
    val navController = rememberNavController()
    val items = listOf(
        Screen.Calendar,
        Screen.Timer,
        Screen.Settings
    )

    // 딥링크 또는 인텐트를 통한 탭 이동 처리 (요구사항 4번)
    LaunchedEffect(startRoute) {
        if (startRoute != null) {
            navController.navigate(startRoute) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            if (screen == Screen.Timer) {
                                viewModel.clearSelectionIfNotActive()
                            }
                            navController.navigate(screen.route) {
                                // 기존 백스택을 정리하여 화면 겹침 방지
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = false // 상태 저장을 끄고 새로 로드하여 겹침 방지 (v1.2.5 개선)
                                }
                                launchSingleTop = true
                                restoreState = false // 상태 복원도 꺼서 깨끗한 화면 보장
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Calendar.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Calendar.route) { 
                CalendarScreen(viewModel, onNavigateToTimer = {
                    navController.navigate(Screen.Timer.route) {
                        popUpTo(Screen.Calendar.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }) 
            }
            composable(Screen.Timer.route) { 
                SchedulerScreen(viewModel, onNavigateToCalendar = {
                    navController.navigate(Screen.Calendar.route) {
                        // 캘린더로 돌아갈 때 모든 백스택을 비워 레이어 중첩 방지
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }) 
            }
            composable(Screen.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}

