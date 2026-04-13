package com.focusflow.app.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.focusflow.app.ui.viewmodel.SchedulerViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Calendar : Screen("calendar", "캘린더", Icons.Default.DateRange)
    object Timer : Screen("timer", "타이머", Icons.Default.PlayArrow)
    object Settings : Screen("settings", "설정", Icons.Default.Settings)
}

@Composable
fun MainScreen(
    viewModel: SchedulerViewModel, 
    rootNavController: NavController,
    startRoute: String? = null
) {
    val navController = rememberNavController() // Internal NavController for bottom tabs
    
    // Handle startRoute for deep links
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
    val items = listOf(
        Screen.Calendar,
        Screen.Timer,
        Screen.Settings
    )

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
                            // If we are currently on the calendar screen and navigating to a different screen
                            if (currentDestination?.route == Screen.Calendar.route && screen.route != Screen.Calendar.route) {
                                viewModel.clearSelectedBlocks()
                            }
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Timer.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Calendar.route) { 
                CalendarScreen(viewModel, onNavigateToTimer = {
                    navController.navigate(Screen.Timer.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) 
            }
            composable(Screen.Timer.route) { 
                SchedulerScreen(viewModel, onNavigateToCalendar = {
                    navController.navigate(Screen.Calendar.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) 
            }
            composable(Screen.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}
