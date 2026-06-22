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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Режим эмулируемой метки — то самое требование ТЗ «уязвимая / защищённая метка». */
enum class BeaconMode { PROTECTED, VULNERABLE }

/** Мощность передатчика BLE-вещания (TZ: настраиваемый TxPower). */
enum class BeaconTxPower(val level: Int) {
    LOW(AdvertiseSettings.ADVERTISE_TX_POWER_LOW),
    MEDIUM(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM),
    HIGH(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH),
}

data class EmulatorUiState(
    val uuid: String,
    val mode: BeaconMode = BeaconMode.PROTECTED,
    val txPower: BeaconTxPower = BeaconTxPower.HIGH,
    val advertising: Boolean = false,
    val supported: Boolean = true,
    // Текущий счётчик времени (виден в защищённом режиме — наглядно, что код ротируется).
    val counter: Long = 0,
    @StringRes val statusRes: Int = R.string.emu_status_idle,
)

/**
 * Состояние приложения-эмулятора метки. Управляет [BeaconAdvertiser] и в защищённом режиме
 * сам ротирует код через [BeaconCode], переиспользуя тот же секрет, которым гид проверяет
 * подлинность ([BeaconAuthKeyProvider] из [AppContainer], секреты — [DemoBeaconSecrets]).
 *
 * Это «вторая половина» демо «атака vs защита» из ТЗ: гид валидирует, эмулятор вещает либо
 * подлинную, либо поддельную метку.
 */
class BeaconEmulatorViewModel(
    private val advertiser: BeaconAdvertiser,
    private val keyProvider: BeaconAuthKeyProvider,
    val availableUuids: List<String>,
) : ViewModel() {

    private val _state = MutableStateFlow(
        EmulatorUiState(
            uuid = availableUuids.firstOrNull().orEmpty(),
            supported = advertiser.isSupported,
        )
    )
    val state: StateFlow<EmulatorUiState> = _state.asStateFlow()

    // Корутина вещания: в защищённом режиме крутит ротацию кода, в уязвимом вещает однократно.
    private var advertiseJob: Job? = null

    fun selectUuid(uuid: String) = reapply { it.copy(uuid = uuid) }
    fun selectMode(mode: BeaconMode) = reapply { it.copy(mode = mode) }
    fun selectTxPower(txPower: BeaconTxPower) = reapply { it.copy(txPower = txPower) }

    /** Применить изменение настройки; если уже вещаем — перезапустить с новыми параметрами. */
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
            // Защищённая метка: переиздаём пакет на каждой смене 30-секундного счётчика,
            // чтобы перехваченный код быстро протухал (replay-стойкость).
            var lastCounter = -1L
            while (isActive) {
                val counter = BeaconCode.counterAt(System.currentTimeMillis())
                if (counter != lastCounter) {
                    lastCounter = counter
                    val payload = keyProvider.macFor(_state.value.uuid)
                        ?.let { BeaconCode.payload(it, counter) }
                    if (payload == null) {
                        // Нет секрета для выбранного UUID — генерировать код нечем.
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
                    availableUuids = container.demoBeaconUuids,
                )
            }
        }
    }
}
