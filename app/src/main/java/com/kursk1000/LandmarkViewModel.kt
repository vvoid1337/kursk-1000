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
    foreground: StateFlow<Boolean>,
) : ViewModel() {

    val scanState: StateFlow<ScanState> = scanner.scanState
    val visibleBeacons: StateFlow<List<BeaconInfo>> = scanner.visibleBeacons

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

    // Карточка ближайшего маяка. Чистая функция от (маяк, кэш): UUID, который видит
    // сканер, всегда в белом списке = в кэше, поэтому резолвится локально без сети.
    // «Сырое» реактивное состояние: следует за эфиром напрямую (маяк пропал → Searching).
    // Наружу не отдаётся — поверх него живёт залипающее _uiState (см. ниже).
    private val rawUiState: StateFlow<UiState> =
        // По UUID, а не по сырому BeaconInfo: detectedBeacon обновляется каждый свип
        // (RSSI/lastSeen), и без distinctUntilChanged карточка переэмитилась бы каждую секунду.
        combine(
            scanner.detectedBeacon.map { it?.uuid }.distinctUntilChanged(),
            load,
        ) { uuid, load ->
            if (uuid == null) {
                UiState.Searching
            } else {
                (load as? LandmarkLoad.Ready)
                    ?.byUuid
                    ?.get(uuid.uppercase())
                    ?.let { UiState.Loaded(it) }
                    ?: UiState.Searching
            }
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Searching)

    // Залипающее состояние карточки. Открытая достопримечательность держится на экране,
    // даже когда маяк ушёл из эфира (Searching), — закрывает её только пользователь
    // (dismissCard). Новый маяк или повторный заход к тому же (после выхода из зоны)
    // обновляют/открывают карточку заново.
    private val _uiState = MutableStateFlow<UiState>(UiState.Searching)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()

        // Переносим «сырое» состояние в залипающее: проходит всё, кроме пропажи маяка
        // (Searching) поверх уже открытой карточки — её гасит только dismissCard().
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
                // Снятие скана чистит visibleBeacons и detectedBeacon в сканере — то самое
                // «забыть отсканированное»: после паузы маяк определяется заново (null→UUID).
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
     * (сканер останавливается → visibleBeacons/detectedBeacon чистятся) и держим паузу 3 с,
     * прежде чем снова сканировать. По истечении паузы скан возобновляется сам, и если маяк
     * рядом — карточка откроется заново (null→UUID), не требуя отходить от метки.
     */
    fun dismissCard() {
        _uiState.value = UiState.Searching
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
                    foreground = container.appForegroundMonitor.appInForeground,
                )
            }
        }
    }
}
