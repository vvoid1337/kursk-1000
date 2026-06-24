package com.kursk1000

import android.bluetooth.le.AdvertiseSettings
import androidx.annotation.StringRes
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** режим маяка */
enum class BeaconMode { PROTECTED, VULNERABLE }

/** txpower */
enum class BeaconTxPower(val level: Int) {
    LOW(AdvertiseSettings.ADVERTISE_TX_POWER_LOW),
    MEDIUM(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM),
    HIGH(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH),
}

data class EmulatorUiState(
    val uuid: String = "",
    val mode: BeaconMode = BeaconMode.PROTECTED,
    val txPower: BeaconTxPower = BeaconTxPower.HIGH,
    val advertising: Boolean = false,
    val supported: Boolean = true,
    // текущий счётчик времени
    val counter: Long = 0,
    @StringRes val statusRes: Int = R.string.emu_status_idle,
)

/**
Эмулятор метки через BeaconAdvertiser транслирует динамический код,
Ротируемый BeaconCode с секретом из Keystore (BeaconAuthKeyProvider).
Секреты и UUID загружаются с бекенда через LandmarkRepository.
 */
class BeaconEmulatorViewModel(
    private val advertiser: BeaconAdvertiser,
    private val keyProvider: BeaconAuthKeyProvider,
    repository: LandmarkRepository,
) : ViewModel() {

    // UUID доступных меток - из того же кэша, что и у гида
    val availableUuids: StateFlow<List<String>> =
        repository.landmarks
            .map { load -> (load as? LandmarkLoad.Ready)?.byUuid?.keys?.sorted().orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(EmulatorUiState(supported = advertiser.isSupported))
    val state: StateFlow<EmulatorUiState> = _state.asStateFlow()

    // Корутина вещания: в защищённом режиме крутит ротацию кода, в уязвимом вещает однократно
    private var advertiseJob: Job? = null

    init {
        // Тянем список меток и секреты (refresh → Keystore) с бекенда - без сети вещать нечем.
        viewModelScope.launch { repository.refresh() }
        // Как только метки приехали, выбираем первую, если пользователь ещё ничего не выбрал.
        viewModelScope.launch {
            availableUuids.collect { uuids ->
                if (_state.value.uuid.isEmpty() && uuids.isNotEmpty()) {
                    _state.update { it.copy(uuid = uuids.first()) }
                }
            }
        }
    }

    fun selectUuid(uuid: String) = reapply { it.copy(uuid = uuid) }
    fun selectMode(mode: BeaconMode) = reapply { it.copy(mode = mode) }
    fun selectTxPower(txPower: BeaconTxPower) = reapply { it.copy(txPower = txPower) }

    /** применить изменение настройки; если уже вещаем - перезапустить с новыми параметрами. */
    private fun reapply(change: (EmulatorUiState) -> EmulatorUiState) {
        _state.update(change)
        if (_state.value.advertising) startAdvertising()
    }

    fun toggleAdvertising() {
        if (_state.value.advertising) stopAdvertising() else startAdvertising()
    }

    private fun startAdvertising() {
        if (!advertiser.isSupported) {
            _state.update { it.copy(advertising = false, supported = false, statusRes = R.string.emu_status_unsupported) }
            return
        }
        _state.update { it.copy(advertising = true, supported = true, statusRes = R.string.emu_status_starting) }

        advertiseJob?.cancel()
        advertiseJob = viewModelScope.launch {
            if (_state.value.mode == BeaconMode.VULNERABLE) {
                // Уязвимая метка: только UUID, без кода. Вещаем один раз — ротировать нечего.
                issue(serviceData = null, statusRes = R.string.emu_status_advertising_vulnerable)
                return@launch
            }
            // Защищённая метка: переиздаём пакет на каждой смене счётчика,
            // чтобы перехваченный код быстро протухал
            var lastCounter = -1L
            while (isActive) {
                val counter = BeaconCode.counterAt(System.currentTimeMillis())
                if (counter != lastCounter) {
                    lastCounter = counter
                    val payload = keyProvider.macFor(_state.value.uuid)
                        ?.let { BeaconCode.payload(it, counter) }
                    if (payload == null) {
                        // нет секрета для выбранного UUID - генерировать код нечем.
                        _state.update { it.copy(statusRes = R.string.emu_status_no_secret) }
                        return@launch
                    }
                    issue(serviceData = payload, statusRes = R.string.emu_status_advertising_protected)
                }
                _state.update { it.copy(counter = counter) }
                delay(1_000)
            }
        }
    }

    private fun issue(serviceData: ByteArray?, @StringRes statusRes: Int) {
        val s = _state.value
        advertiser.start(s.uuid, serviceData, s.txPower.level) { result ->
            _state.update {
                if (result.isSuccess) it.copy(statusRes = statusRes)
                else it.copy(advertising = false, statusRes = R.string.emu_status_failed)
            }
        }
    }

    private fun stopAdvertising() {
        advertiseJob?.cancel()
        advertiseJob = null
        advertiser.stop()
        _state.update { it.copy(advertising = false, statusRes = R.string.emu_status_idle) }
    }

    override fun onCleared() {
        super.onCleared()
        advertiseJob?.cancel()
        advertiser.stop()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Kursk1000App
                val container = app.container
                BeaconEmulatorViewModel(
                    advertiser = container.createBeaconAdvertiser(),
                    keyProvider = container.beaconAuthKeyProvider,
                    repository = container.landmarkRepository,
                )
            }
        }
    }
}
