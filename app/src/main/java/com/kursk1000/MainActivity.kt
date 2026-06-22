package com.kursk1000

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.kursk1000.ui.theme.Kursk1000Theme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Kursk1000Theme {
                BleScreen()
            }
        }
        applyStatusBarVisibility(resources.configuration)
    }

    // Activity не пересоздаётся при повороте (configChanges в манифесте), поэтому
    // ловим смену ориентации здесь.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBarVisibility(newConfig)
    }

    // в альбомной ориентации прячем статус-бар
    // в портретной — показываем обратно
    private fun applyStatusBarVisibility(config: Configuration) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // фикс белого на белом в статус баре, если на девайсе тёмная тема
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun BleScreen(viewModel: LandmarkViewModel = viewModel(factory = LandmarkViewModel.Factory)) {
    // Всё состояние живёт в ViewModel и переживает поворот экрана — список не
    // перезапрашивается, карточка не сбрасывается при пересоздании Activity.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val visibleBeacons by viewModel.visibleBeacons.collectAsStateWithLifecycle()

    // На Android 12+ сканирование объявлено с флагом neverForLocation, поэтому
    // разрешение на геолокацию не требуется. BLUETOOTH_CONNECT не нужен — мы только сканируем.
    val permissionsList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissions = rememberMultiplePermissionsState(permissions = permissionsList)

    // Прокидываем статус разрешения в ViewModel — она решает, запускать ли скан.
    LaunchedEffect(permissions.allPermissionsGranted) {
        viewModel.setPermissionGranted(permissions.allPermissionsGranted)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            val scanError = (scanState as? ScanState.Error)?.message
            val loadError = (load as? LandmarkLoad.Failed)?.message
            // Кэш есть, но последнее обновление по сети упало — покажем ненавязчивый баннер (ниже).
            val refreshError = (load as? LandmarkLoad.Ready)?.refreshError

            if (!permissions.allPermissionsGranted) {
                PermissionRequest(onRequest = { permissions.launchMultiplePermissionRequest() })
            } else if (loadError != null) {
                ErrorScreen(loadError, onRetry = { viewModel.refresh() })
            } else if (scanError != null) {
                // Ошибки Bluetooth восстанавливаются автоматически (см. BleScanner) — кнопка не нужна
                ErrorScreen(scanError)
            } else {
                AnimatedContent(
                    targetState = uiState,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(120)) togetherWith
                            fadeOut(animationSpec = tween(90))
                    },
                    label = "ui_state_content",
                ) { state ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        when (state) {
                            is UiState.Searching -> SearchingScreen(
                                beacons = visibleBeacons,
                                isScanning = scanState is ScanState.Scanning,
                            )
                            is UiState.Loaded -> {
                                // Системная кнопка «Назад» закрывает карточку так же, как крестик,
                                // а не сворачивает/закрывает приложение. Перехват активен только
                                // пока карточка открыта (state == Loaded).
                                BackHandler { viewModel.dismissCard() }
                                LandmarkCard(state.landmark, onClose = { viewModel.dismissCard() })
                            }
                            is UiState.Untrusted -> UntrustedScreen(state.name)
                        }
                    }
                }
            }

            // Баннер «нет связи»: только на экране поиска. Поверх открытой карточки
            // (Loaded), экрана запроса разрешений и экранов ошибок его не рисуем — иначе
            // он перекрывает контент и крестик карточки.
            if (refreshError != null &&
                permissions.allPermissionsGranted &&
                scanError == null &&
                uiState is UiState.Searching
            ) {
                StaleDataBanner(
                    modifier = Modifier.align(Alignment.TopCenter),
                    onRetry = { viewModel.refresh() },
                )
            }
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.permission_required))
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.grant_permissions)) }
    }
}

@Composable
private fun SearchingScreen(
    beacons: List<BeaconInfo>,
    isScanning: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        LandmarkRadar(
            beacons = beacons,
            isScanning = isScanning,
            modifier = Modifier.size(224.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.searching_landmarks),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.move_closer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// Метка найдена, но не прошла проверку подлинности (TZ Вариант А) — показываем
// предупреждение вместо карточки. Это видимый итог защиты от спуфинга/клонирования.
@Composable
private fun UntrustedScreen(name: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Text("🛡️", style = MaterialTheme.typography.displayLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.beacon_untrusted_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.beacon_untrusted_message, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StaleDataBanner(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Surface(
        // fillMaxWidth + weight на тексте: длинная строка больше не выталкивает кнопку
        // «Повторить» за край экрана (из-за чего на части устройств её было не видно).
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.offline_stale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Text("⚠️", style = MaterialTheme.typography.displayLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.error_message, message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchingScreenPreview() {
    Kursk1000Theme {
        SearchingScreen(
            beacons = listOf(
                BeaconInfo("A1B2C3D4-E5F6-7890-ABCD-EF1234567890", -48),
                BeaconInfo("B1B2C3D4-E5F6-7890-ABCD-EF1234567890", -65),
            ),
            isScanning = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ErrorScreenPreview() {
    Kursk1000Theme { ErrorScreen("HTTP 500", onRetry = {}) }
}

@Preview(showBackground = true)
@Composable
private fun PermissionRequestPreview() {
    Kursk1000Theme { PermissionRequest(onRequest = {}) }
}
