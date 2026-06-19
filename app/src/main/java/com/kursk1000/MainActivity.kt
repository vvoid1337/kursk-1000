package com.kursk1000

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
                Crossfade(targetState = uiState, label = "ui_state_crossfade") { state ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        when (state) {
                            is UiState.Searching -> SearchingScreen()
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
        Text("Нужны разрешения для сканирования")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRequest) { Text("Дать разрешения") }
    }
}

@Composable
private fun SearchingScreen() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Text("🔍", style = MaterialTheme.typography.displayLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Поиск достопримечательностей...",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Подойдите ближе к объекту",
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
        Text("Загрузка информации...", style = MaterialTheme.typography.bodyMedium)
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
            text = "Ошибка: $message",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Повторить") }
        }
    }
}
