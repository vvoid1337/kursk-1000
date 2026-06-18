package com.kursk1000

import android.Manifest
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.kursk1000.ui.theme.Kursk1000Theme

class MainActivity : ComponentActivity() {

    private lateinit var bleScanner: BleScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleScanner = BleScanner(this)
        enableEdgeToEdge()
        setContent {
            Kursk1000Theme {
                BleScreen(bleScanner)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleScanner.release()
    }
}

// Состояния загрузки данных с бекенда
sealed class UiState {
    data object Searching : UiState()                      // маяк не найден
    data object Loading : UiState()                        // запрос к API
    data class Loaded(val landmark: Landmark) : UiState()  // данные получены
    data class ApiError(val message: String) : UiState()   // ошибка API
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun BleScreen(bleScanner: BleScanner) {
    val detectedBeacon by bleScanner.detectedBeacon.collectAsState()
    val scanState by bleScanner.scanState.collectAsState()

    var uiState by remember { mutableStateOf<UiState>(UiState.Searching) }

    // Полный кэш карточек из GET /landmarks (uuid в верхнем регистре → Landmark).
    // Список теперь несёт весь контент, поэтому это единственный источник данных —
    // карточку показываем локально, без отдельного запроса (фундамент под Room-кэш).
    // null — список ещё не загружен.
    var landmarksByUuid by remember { mutableStateOf<Map<String, Landmark>?>(null) }
    // Ошибка загрузки списка (отличаем сетевую ошибку от пустого списка)
    var loadError by remember { mutableStateOf<String?>(null) }
    // Счётчик повторных попыток: инкремент перезагружает список и перезапускает сканирование
    var retryTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryTrigger) {
        landmarksByUuid = null
        loadError = null
        when (val result = fetchLandmarks()) {
            is LandmarksResult.Success ->
                landmarksByUuid = result.landmarks.associateBy { it.uuid.uppercase() }
            is LandmarksResult.Error -> loadError = result.message
        }
    }

    // Карточку берём из локального кэша по UUID ближайшего маяка — без обращения к сети.
    // Точечный запрос оставлен как подстраховка, если объекта вдруг нет в списке.
    LaunchedEffect(detectedBeacon?.uuid, landmarksByUuid) {
        val uuid = detectedBeacon?.uuid
        if (uuid == null) {
            uiState = UiState.Searching
            return@LaunchedEffect
        }

        val cached = landmarksByUuid?.get(uuid.uppercase())
        if (cached != null) {
            uiState = UiState.Loaded(cached)
            return@LaunchedEffect
        }

        uiState = UiState.Loading
        uiState = when (val result = fetchLandmark(uuid)) {
            is LandmarkResult.Success  -> UiState.Loaded(result.landmark)
            is LandmarkResult.NotFound -> UiState.ApiError("Объект не найден в базе")
            is LandmarkResult.Error    -> UiState.ApiError(result.message)
        }
    }

    // На Android 12+ сканирование объявлено с флагом neverForLocation, поэтому
    // разрешение на геолокацию не требуется. BLUETOOTH_CONNECT не нужен — мы только сканируем.
    val permissionsList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissions = rememberMultiplePermissionsState(permissions = permissionsList)

    // Сканируем только когда приложение на переднем плане — иначе зря тратим батарею
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isForeground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    LaunchedEffect(permissions.allPermissionsGranted, landmarksByUuid, isForeground) {
        val uuids = landmarksByUuid?.keys
        if (isForeground && permissions.allPermissionsGranted && uuids != null) bleScanner.startScan(uuids)
        else bleScanner.stopScan()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            val scanError = (scanState as? ScanState.Error)?.message

            if (!permissions.allPermissionsGranted) {
                PermissionRequest(onRequest = { permissions.launchMultiplePermissionRequest() })
            } else if (loadError != null) {
                ErrorScreen(loadError!!, onRetry = { retryTrigger++ })
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
                            is UiState.Loaded    -> LandmarkCard(state.landmark)
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
