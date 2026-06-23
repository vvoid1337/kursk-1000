package com.kursk1000

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LandmarkRadar(
    beacons: List<BeaconInfo>,
    isScanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "landmark_radar")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing)),
        label = "radar_sweep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radar_pulse",
    )
    val colors = MaterialTheme.colorScheme
    // Точки радара — чистое отображение списка меток (без сайд-эффекта в мутабельный список):
    // на каждую метку анимируем «силу сигнала» из RSSI. Ключ — составной (address+uuid):
    // на одном UUID могут быть два устройства в сыром списке, а address может быть пустым
    // или одинаковым (Android 12+ placeholder). Составной ключ гарантирует отсутствие коллизий.
    val radarPoints = beacons.sortedBy { it.deviceAddress }.map { beacon ->
        key(beacon.deviceAddress + beacon.uuid) {
            val signal by animateFloatAsState(
                targetValue = ((beacon.rssi + 75f) / 40f).coerceIn(0f, 1f),
                animationSpec = tween(450, easing = FastOutSlowInEasing),
                label = "beacon_signal",
            )
            RadarPoint(beacon, signal)
        }
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.42f
        val ringColor = colors.outlineVariant.copy(alpha = 0.9f)
        val activeColor = colors.primary

        drawCircle(
            color = colors.surfaceVariant.copy(alpha = 0.25f),
            radius = radius,
            center = center,
        )
        listOf(0.33f, 0.66f, 1f).forEach { fraction ->
            drawCircle(
                color = ringColor,
                radius = radius * fraction,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        drawLine(
            color = ringColor,
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = ringColor,
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 1.dp.toPx(),
        )

        drawArc(
            color = activeColor.copy(alpha = if (isScanning) 0.16f else 0.07f),
            startAngle = sweepAngle - 46f,
            sweepAngle = 46f,
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
        )
        val angleRadians = sweepAngle.toDouble() * PI / 180.0
        drawLine(
            color = activeColor.copy(alpha = if (isScanning) 0.85f else 0.4f),
            start = center,
            end = Offset(
                center.x + (cos(angleRadians) * radius).toFloat(),
                center.y + (sin(angleRadians) * radius).toFloat(),
            ),
            strokeWidth = 2.dp.toPx(),
        )

        radarPoints.forEach { (beacon, signal) ->
            val angle = stableAngle(beacon.uuid)
            val distance = radius * (0.82f - signal * 0.58f)
            val angleRadians = angle.toDouble() * PI / 180.0
            val position = Offset(
                center.x + (cos(angleRadians) * distance).toFloat(),
                center.y + (sin(angleRadians) * distance).toFloat(),
            )
            val pointRadius = 5.dp.toPx() + signal * 3.dp.toPx()

            drawCircle(
                color = activeColor.copy(alpha = 0.22f * pulse),
                radius = pointRadius * 2.2f,
                center = position,
            )
            drawCircle(color = activeColor, radius = pointRadius, center = position)
            drawCircle(
                color = colors.onPrimary,
                radius = 1.5.dp.toPx(),
                center = position,
            )
        }

        drawCircle(
            color = activeColor.copy(alpha = if (isScanning) pulse else 0.45f),
            radius = 9.dp.toPx(),
            center = center,
        )
        drawCircle(color = colors.onPrimary, radius = 3.dp.toPx(), center = center)
    }
}

private fun stableAngle(uuid: String): Float {
    val hash = uuid.fold(0) { value, char -> 31 * value + char.code }
    // Модуль берём в целочисленной арифметике ДО конвертации в Float: иначе большие хэши
    // теряют точность (мантисса Float — 24 бита) и разные метки схлопываются в один угол.
    return ((hash.toLong() and 0x7fff_ffffL) % 360L).toFloat()
}

private data class RadarPoint(
    val beacon: BeaconInfo,
    val signal: Float,
)
