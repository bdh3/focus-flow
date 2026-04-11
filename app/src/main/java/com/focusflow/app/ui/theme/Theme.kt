package com.focusflow.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight
)

@Composable
fun FocusFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    fontSizeScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    // 폰트 크기 조절을 위한 타이포그래피 커스텀
    val baseTypography = Typography
    val scaledTypography = androidx.compose.material3.Typography(
        displayLarge = baseTypography.displayLarge.copy(fontSize = baseTypography.displayLarge.fontSize * fontSizeScale),
        displayMedium = baseTypography.displayMedium.copy(fontSize = baseTypography.displayMedium.fontSize * fontSizeScale),
        displaySmall = baseTypography.displaySmall.copy(fontSize = baseTypography.displaySmall.fontSize * fontSizeScale),
        headlineLarge = baseTypography.headlineLarge.copy(fontSize = baseTypography.headlineLarge.fontSize * fontSizeScale),
        headlineMedium = baseTypography.headlineMedium.copy(fontSize = baseTypography.headlineMedium.fontSize * fontSizeScale),
        headlineSmall = baseTypography.headlineSmall.copy(fontSize = baseTypography.headlineSmall.fontSize * fontSizeScale),
        titleLarge = baseTypography.titleLarge.copy(fontSize = baseTypography.titleLarge.fontSize * fontSizeScale),
        titleMedium = baseTypography.titleMedium.copy(fontSize = baseTypography.titleMedium.fontSize * fontSizeScale),
        titleSmall = baseTypography.titleSmall.copy(fontSize = baseTypography.titleSmall.fontSize * fontSizeScale),
        bodyLarge = baseTypography.bodyLarge.copy(fontSize = baseTypography.bodyLarge.fontSize * fontSizeScale),
        bodyMedium = baseTypography.bodyMedium.copy(fontSize = baseTypography.bodyMedium.fontSize * fontSizeScale),
        bodySmall = baseTypography.bodySmall.copy(fontSize = baseTypography.bodySmall.fontSize * fontSizeScale),
        labelLarge = baseTypography.labelLarge.copy(fontSize = baseTypography.labelLarge.fontSize * fontSizeScale),
        labelMedium = baseTypography.labelMedium.copy(fontSize = baseTypography.labelMedium.fontSize * fontSizeScale),
        labelSmall = baseTypography.labelSmall.copy(fontSize = baseTypography.labelSmall.fontSize * fontSizeScale)
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use transparent status bar with edge-to-edge
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = scaledTypography,
        content = content
    )
}
