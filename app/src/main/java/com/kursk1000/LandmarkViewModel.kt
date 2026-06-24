package com.kursk1000

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Порог «метка рядом» для открытия карточки (~2–5 м на TxPower HIGH).
// MIN_RSSI в сканере только пускает метку на радар; карточка - строже.
private const val NEAR_RSSI = -60

sealed class UiState {
    data object Searching : UiState()
    data class Loaded(val landmark: Landmark) : UiState()
    // Состояния для поддельной метки нет - ноль реакции на спуфер/клон.
}

/**
 * Держатель состояния экрана. Переживает поворот - список грузится один раз.
 *
 * Зависимости инъектируются через [Factory] - можно подменять в тестах.
 * Скан привязан к жизненному циклу процесса (через [ForegroundMonitor]),
 * а не Activity - поворот экрана не дёргает Bluetooth.
 */
class LandmarkViewModel(
    private val repository: LandmarkRepository,
    private val scanner: BleScanner,
    private val verifier: BeaconVerifier,
    foreground: StateFlow<Boolean>,
) : ViewModel() {

    val scanState: StateFlow<ScanState> = scanner.scanState

    val load: StateFlow<LandmarkLoad> =
        repository.landmarks.stateIn(viewModelScope, SharingStarted.Eagerly, LandmarkLoad.Loading)

    private val permissionGranted = MutableStateFlow(false)
    private val appInForeground: StateFlow<Boolean> = foreground
    private val scanEnabled = MutableStateFlow(true)
    private var dismissPauseJob: Job? = null

    // Единственный тракт проверки меток. Сканер отдаёт сырой список по MAC,
    // здесь отсеиваем подделки - результат кормит и радар, и решение об открытии карточки.
    private val verifiedSweep: StateFlow<Pair<List<BeaconInfo>, UiState>> =
        combine(scanner.visibleBeacons, load) { beacons, load ->
            val ready = load as? LandmarkLoad.Ready
            val verified = if (ready != null) {
                beacons.filter { beacon ->
                    ready.byUuid.containsKey(beacon.uuid.uppercase()) &&
                        verifier.verify(beacon.uuid, beacon.authData) == BeaconAuthState.AUTHENTIC
                }
            } else emptyList()
            val landmark = verified
                .filter { it.rssi >= NEAR_RSSI }
                .maxByOrNull { it.rssi }
                ?.let { ready?.byUuid?.get(it.uuid.uppercase()) }
            val state = if (landmark != null) UiState.Loaded(landmark) else UiState.Searching
            verified to state
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<BeaconInfo>() to UiState.Searching)

    /** Только подлинные метки - для радара. */
    val visibleBeacons: StateFlow<List<BeaconInfo>> =
        verifiedSweep.map { it.first }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val rawUiState: StateFlow<UiState> =
        verifiedSweep.map { it.second }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Searching)

    // Залипающее состояние: открытая карточка держится, даже если маяк ушёл из эфира.
    // Закрывает только пользователь (dismissCard).
    private val _uiState = MutableStateFlow<UiState>(UiState.Searching)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()

        // Липнем только в Loaded←Searching. Пропажа метки из эфира карточку не закрывает.
        viewModelScope.launch {
            rawUiState.collect { raw ->
                if (raw is UiState.Searching && _uiState.value is UiState.Loaded) return@collect
                _uiState.value = raw
            }
        }

        // Сканируем только когда: приложение на переднем плане + разрешение + белый список не пуст.
        viewModelScope.launch {
            combine(appInForeground, permissionGranted, load, scanEnabled) { fg, granted, loaded, enabled ->
                val uuids = (loaded as? LandmarkLoad.Ready)?.byUuid?.keys
                val shouldScan = fg && granted && enabled && uuids?.isNotEmpty() == true
                shouldScan to uuids
            }.distinctUntilChanged().collect { (shouldScan, uuids) ->
                if (shouldScan && uuids != null) scanner.startScan(uuids)
                else scanner.stopScan()
            }
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        permissionGranted.value = granted
    }

    /**
     * Закрыть карточку (крестик / Back). Останавливает скан, чистит видимые метки,
     * держит паузу 3 с чтобы карточка не открылась тут же снова.
     */
    fun dismissCard() {
        _uiState.value = UiState.Searching
        // Анти-replay историю не сбрасываем: сброс на каждом закрытии открыл бы окно для переигрывания.
        scanEnabled.value = false
        dismissPauseJob?.cancel()
        dismissPauseJob = viewModelScope.launch {
            delay(3_000)
            scanEnabled.value = true
        }
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    override fun onCleared() {
        super.onCleared()
        scanner.release()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Kursk1000App
                val container = app.container
                LandmarkViewModel(
                    repository = container.landmarkRepository,
                    scanner = container.createBleScanner(),
                    verifier = container.createBeaconVerifier(),
                    foreground = container.appForegroundMonitor.appInForeground,
                )
            }
        }
    }
}
