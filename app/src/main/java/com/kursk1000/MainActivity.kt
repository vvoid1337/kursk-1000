package com.kursk1000

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
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

    /**
     * В альбомной ориентации прячем статус-бар (на узком по высоте экране он смотрится
     * лишним), в портретной — показываем обратно. Навигационную панель не трогаем.
     */
    private fun applyStatusBarVisibility(config: Configuration) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Приложение всегда светлое (фиксированный light-scheme), поэтому принудительно
        // ставим тёмные иконки системных баров. Иначе на устройстве с тёмной темой
        // enableEdgeToEdge() рисует светлые иконки — белое на белом, баров не видно.
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
                            is UiState.Loading   -> LoadingScreen()
                            is UiState.Loaded    -> LandmarkCard(state.landmark, onClose = { viewModel.dismissCard() })
                            is UiState.ApiError  -> ErrorScreen(state.message)
                        }
                    }
                }
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

@Composable
private fun LoadingScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.loading_info), style = MaterialTheme.typography.bodyMedium)
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
                BeaconInfo("00:11:22:33:44:55", "A1B2C3D4-E5F6-7890-ABCD-EF1234567890", -48),
                BeaconInfo("00:11:22:33:44:56", "B1B2C3D4-E5F6-7890-ABCD-EF1234567890", -65),
            ),
            isScanning = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingScreenPreview() {
    Kursk1000Theme { LoadingScreen() }
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
