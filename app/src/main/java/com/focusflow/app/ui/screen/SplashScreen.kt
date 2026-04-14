package com.focusflow.app.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.app.BuildConfig
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    
    // 애니메이션 설정
    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "Alpha"
    )
    
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.8f,
        animationSpec = tween(durationMillis = 1200, easing = OutwardEasing),
        label = "Scale"
    )

    // 원형 회전 애니메이션 (흐름의 상징)
    val infiniteTransition = rememberInfiniteTransition(label = "Flow")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    LaunchedEffect(key1 = true) {
        startAnimation = true
        delay(2200)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .clickable { onTimeout() },
        contentAlignment = Alignment.Center
    ) {
        val primaryColor = Color(0xFF38BDF8)

        // 중앙 콘텐츠 영역 (로고 + 텍스트)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .scale(scaleAnim)
                .alpha(alphaAnim)
        ) {
            // 로고 영역 (모든 요소가 동심원을 이룸)
            Box(contentAlignment = Alignment.Center) {
                // 1. 배경 은은한 원
                Canvas(modifier = Modifier.size(220.dp)) {
                    drawCircle(
                        color = primaryColor,
                        style = Stroke(width = 0.5.dp.toPx()),
                        alpha = 0.15f
                    )
                }

                // 2. 외부 회전 아크 (Flow)
                Canvas(modifier = Modifier.size(140.dp)) {
                    drawArc(
                        color = primaryColor,
                        startAngle = rotation,
                        sweepAngle = 100f,
                        useCenter = false,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // 3. 커스텀 타이머 로고 (Focus)
                Canvas(modifier = Modifier.size(80.dp)) {
                    val radius = size.minDimension / 2
                    val strokeWidth = 2.5.dp.toPx()

                    // 시계 몸체 (원)
                    drawCircle(
                        color = primaryColor,
                        style = Stroke(width = strokeWidth)
                    )

                    // 상단 버튼 (타이머)
                    drawLine(
                        color = primaryColor,
                        start = Offset(center.x, center.y - radius - 2.dp.toPx()),
                        end = Offset(center.x, center.y - radius - 10.dp.toPx()),
                        strokeWidth = 6.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // 시계 눈금 (12, 3, 6, 9시)
                    for (i in 0 until 4) {
                        val angleDeg = i * 90f
                        val angleRad = Math.toRadians(angleDeg.toDouble())
                        val startDist = radius - 8.dp.toPx()
                        val endDist = radius - 3.dp.toPx()
                        
                        drawLine(
                            color = primaryColor,
                            start = Offset(
                                (center.x + startDist * cos(angleRad)).toFloat(),
                                (center.y + startDist * sin(angleRad)).toFloat()
                            ),
                            end = Offset(
                                (center.x + endDist * cos(angleRad)).toFloat(),
                                (center.y + endDist * sin(angleRad)).toFloat()
                            ),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // 시계 바늘
                    val handAngleRad = Math.toRadians(-60.0) // 2시 방향
                    drawLine(
                        color = primaryColor,
                        start = center,
                        end = Offset(
                            (center.x + (radius * 0.6f) * cos(handAngleRad)).toFloat(),
                            (center.y + (radius * 0.6f) * sin(handAngleRad)).toFloat()
                        ),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // 중심점
                    drawCircle(
                        color = primaryColor,
                        radius = 3.dp.toPx()
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "FOCUS FLOW",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraLight,
                    letterSpacing = 8.sp,
                    color = Color.White
                )
            )
        }

        // 하단 버전 정보
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White.copy(alpha = 0.2f),
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
                .alpha(alphaAnim)
        )
    }
}

private val OutwardEasing = Easing { fraction ->
    1f - (1f - fraction) * (1f - fraction)
}
