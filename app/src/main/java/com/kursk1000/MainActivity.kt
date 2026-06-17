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
        bleScanner.stopScan()
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

    var uiState by remember { mutableStateOf<UiState>(UiState.Searching) }
    var lastFetchedUuid by remember { mutableStateOf<String?>(null) }

    // Запрашиваем бекенд когда меняется UUID ближайшего маяка
    LaunchedEffect(detectedBeacon?.uuid) {
        val uuid = detectedBeacon?.uuid

        if (uuid == null) {
            uiState = UiState.Searching
            lastFetchedUuid = null
            return@LaunchedEffect
        }

        // Не дёргаем API повторно для того же UUID
        if (uuid == lastFetchedUuid) return@LaunchedEffect

        lastFetchedUuid = uuid
        uiState = UiState.Loading

        uiState = when (val result = fetchLandmark(uuid)) {
            is LandmarkResult.Success  -> UiState.Loaded(result.landmark)
            is LandmarkResult.NotFound -> UiState.ApiError("Объект не найден в базе")
            is LandmarkResult.Error    -> UiState.ApiError(result.message)
        }
    }

    val permissionsList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissions = rememberMultiplePermissionsState(permissions = permissionsList)

    LaunchedEffect(permissions.allPermissionsGranted) {
        if (permissions.allPermissionsGranted) bleScanner.startScan()
        else bleScanner.stopScan()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (!permissions.allPermissionsGranted) {
                PermissionRequest(onRequest = { permissions.launchMultiplePermissionRequest() })
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
private fun ErrorScreen(message: String) {
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
    }
}

@Composable
fun LandmarkCard(landmark: Landmark) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "${landmark.emoji} ${landmark.name}",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Основан в ${landmark.year} году",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = landmark.description, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "📅 К 1000-летию Курска",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = landmark.fact,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
