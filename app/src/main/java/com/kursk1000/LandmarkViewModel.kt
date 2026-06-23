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

// Порог «метка рядом» для открытия карточки (TZ §3: срабатывать только при приближении, ~2–5 м).
// Строже, чем RealBleScanner.MIN_RSSI (-75) — тот лишь пускает метку на радар; карточку открываем
// только когда сигнал близкий. Значение эмпирическое: на TxPower метки HIGH ≈ пара метров.
private const val NEAR_RSSI = -60

// Состояние карточки на экране: формирует его ViewModel, рендерит — BleScreen.
sealed class UiState {
    data object Searching : UiState()                      // подлинная метка рядом не найдена
    data class Loaded(val landmark: Landmark) : UiState()  // данные получены, метка подлинная
    // Намеренно НЕТ состояния для поддельной метки: на спуфер/клон реакции нет вовсе — ни
    // карточки, ни предупреждения. Если рядом одновременно подлинная и поддельная метка,
    // открывается подлинная; если только поддельная — остаёмся в Searching. См. фильтрацию
    // по подлинности ниже (TZ Вариант А: ноль реакции на подделку).
}

/**
 * Держатель состояния экрана. Живёт дольше Activity, поэтому переживает поворот экрана
 * и прочие config changes: список достопримечательностей грузится один раз (в init),
 * а не перезапрашивается на каждый пересоздание Activity.
 *
 * Зависимости инъектируются (см. [Factory]): [LandmarkRepository] — откуда брать
 * карточки (сейчас offline-first Room-кэш), [BleScanner] — поиск маяков. Раньше
 * ViewModel сама создавала сканер и звала свободные сетевые функции — теперь это швы,
 * которые можно подменить фейками в JVM-тестах.
 *
 * Логика включения сканирования завязана на жизненный цикл ВСЕГО приложения
 * (через инъектируемый [ForegroundMonitor] поверх ProcessLifecycleOwner), а не Activity:
 * при повороте Activity проходит stop→start, что иначе зря дёргало бы скан.
 */
class LandmarkViewModel(
    private val repository: LandmarkRepository,
    private val scanner: BleScanner,
    private val verifier: BeaconVerifier,
    foreground: StateFlow<Boolean>,
) : ViewModel() {

    val scanState: StateFlow<ScanState> = scanner.scanState

    // Состояние загрузки списка приходит из репозитория. Eagerly: поток держим горячим
    // всегда — скан-гейтинг ниже подписан на него из init и не зависит от подписки UI.
    val load: StateFlow<LandmarkLoad> =
        repository.landmarks.stateIn(viewModelScope, SharingStarted.Eagerly, LandmarkLoad.Loading)

    // Разрешение на BLE-скан известно UI (accompanist) — прокидываем его внутрь.
    private val permissionGranted = MutableStateFlow(false)
    // Приложение на переднем плане (по процессу, не по Activity) — инъектируется снаружи.
    private val appInForeground: StateFlow<Boolean> = foreground
    // Разрешено ли сейчас сканировать. После ручного закрытия карточки (dismissCard)
    // временно false: даём паузу, чтобы случайное закрытие не переоткрывало карточку сразу.
    private val scanEnabled = MutableStateFlow(true)
    // Корутина паузы скана после закрытия карточки (отменяется при повторном закрытии).
    private var dismissPauseJob: Job? = null

    // ЕДИНСТВЕННЫЙ тракт проверки всех меток. Сканер отдаёт каждое устройство отдельно
    // (ключ — MAC), не зная секретов. Здесь отсеиваем подделки: verify прогоняется один
    // раз на свип, и результат кормит И радар ([visibleBeacons]), И решение об открытии
    // карточки ([rawUiState]). Так поддельная/клонированная метка не даёт НИ точки на
    // радаре, ни карточки — «ноль реакции» (TZ Вариант А).
    //
    // Берём сырой список + load, проверяем каждый маяк; не-Ready load → пустой радар.
    // Затем из близких (RSSI ≥ NEAR_RSSI) берём ближайшую — это кандидат на карточку.
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

    /** Только подлинные метки — для радара. */
    val visibleBeacons: StateFlow<List<BeaconInfo>> =
        verifiedSweep.map { it.first }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Карточка ближайшей подлинной метки (сырая, без sticky). */
    private val rawUiState: StateFlow<UiState> =
        verifiedSweep.map { it.second }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Searching)

    // Залипающее состояние карточки. Открытая достопримечательность держится на экране,
    // даже когда маяк ушёл из эфира (Searching), — закрывает её только пользователь
    // (dismissCard). Новый маяк или повторный заход к тому же (после выхода из зоны)
    // обновляют/открывают карточку заново.
    private val _uiState = MutableStateFlow<UiState>(UiState.Searching)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()

        // Переносим «сырое» состояние в залипающее: липнет ТОЛЬКО переход Loaded←Searching
        // (открытую карточку гасит лишь dismissCard, а не пропажа подлинной метки из эфира).
        // Поддельная метка на состояние не влияет вообще — она не порождает раздельного UiState,
        // поэтому появление спуфера рядом с открытой карточкой её не трогает (ноль реакции).
        viewModelScope.launch {
            rawUiState.collect { raw ->
                if (raw is UiState.Searching && _uiState.value is UiState.Loaded) return@collect
                _uiState.value = raw
            }
        }

        // Сканируем только когда: приложение на переднем плане, есть разрешение и
        // загружен белый список UUID. Не зависит от подписки UI — работает в фоне Activity-recreate.
        viewModelScope.launch {
            combine(appInForeground, permissionGranted, load, scanEnabled) { fg, granted, loaded, enabled ->
                val uuids = (loaded as? LandmarkLoad.Ready)?.byUuid?.keys
                // Пустой белый список (бекенд вернул 0 меток или все UUID битые) — не сканируем,
                // иначе RealBleScanner залипает в ошибке «список пуст» без пути восстановления.
                val shouldScan = fg && granted && enabled && uuids?.isNotEmpty() == true
                shouldScan to uuids
            }.distinctUntilChanged().collect { (shouldScan, uuids) ->
                // Снятие скана чистит visibleBeacons в сканере — то самое «забыть отсканированное»:
                // после паузы метки определяются заново (пустой список → снова появляются).
                if (shouldScan && uuids != null) scanner.startScan(uuids)
                else scanner.stopScan()
            }
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        permissionGranted.value = granted
    }

    /**
     * Закрыть открытую карточку (крестик). Возвращаемся к поиску, «забываем» отсканированное
     * (сканер останавливается → visibleBeacons чистится) и держим паузу 3 с, прежде чем снова
     * сканировать. По истечении паузы скан возобновляется сам, и если подлинная метка рядом —
     * карточка откроется заново, не требуя отходить от метки.
     */
    fun dismissCard() {
        _uiState.value = UiState.Searching
        // Анти-replay историю НЕ сбрасываем: повторный подход к той же метке и так проходит
        // (verify принимает тот же или более новый counter, отвергает лишь строго старее), а сброс
        // на каждом закрытии заново открыл бы окно для переигрывания недавно записанного пакета.
        scanEnabled.value = false
        dismissPauseJob?.cancel()
        dismissPauseJob = viewModelScope.launch {
            delay(3_000)
            scanEnabled.value = true
        }
    }

    /** Перезагрузить список (init и кнопка «Повторить»). Off-load в репозиторий. */
    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    override fun onCleared() {
        super.onCleared()
        // Сканер освобождаем здесь, а не в Activity.onDestroy. Наблюдатель переднего плана
        // теперь живёт в ForegroundMonitor (синглтон процесса), снимать его не нужно.
        scanner.release()
    }

    companion object {
        /**
         * Фабрика для конструкторной инъекции зависимостей в Compose
         * (`viewModel(factory = LandmarkViewModel.Factory)`). Достаёт [AppContainer]
         * из Application и собирает ViewModel из репозитория и свежего BLE-сканера.
         */
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
