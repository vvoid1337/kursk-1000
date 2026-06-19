package com.kursk1000

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class BeaconInfo(
    val address: String,
    val uuid: String,
    val rssi: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
)

// Состояние BLE-сканера для отображения в UI
sealed class ScanState {
    data object Idle : ScanState()                    // сканирование не запущено
    data object Scanning : ScanState()                // активный поиск маяков
    data class Error(val message: String) : ScanState()  // ошибка (Bluetooth выключен, нет прав и т.п.)
}

/**
 * Контракт BLE-сканера, от которого зависит ViewModel. Вынесен в интерфейс, чтобы в
 * тестах подставлять фейк (эмитит в [detectedBeacon]/[scanState]) и проверять
 * залипающее состояние и гейтинг скана без реального Bluetooth и устройства.
 * Боевая реализация — [RealBleScanner].
 */
interface BleScanner {
    val detectedBeacon: StateFlow<BeaconInfo?>
    val scanState: StateFlow<ScanState>
    fun startScan(allowedUuids: Set<String>)
    fun stopScan()
    fun release()
}

class RealBleScanner(private val context: Context) : BleScanner {

    companion object {
        private const val TAG = "BleScanner"
        private const val BEACON_TIMEOUT_MS = 3_000L
        private const val SWEEP_INTERVAL_MS = 1_000L
        private const val MIN_RSSI = -75
        // BALANCED вместо LOW_LATENCY: посетитель подходит к экспонату пешком, разница в
        // отзывчивости незаметна, а батарею непрерывный low-latency-скан ест заметно.
        private const val SCAN_MODE = ScanSettings.SCAN_MODE_BALANCED
    }

    private val _detectedBeacon = MutableStateFlow<BeaconInfo?>(null)
    override val detectedBeacon: StateFlow<BeaconInfo?> = _detectedBeacon.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // SupervisorJob + Main.immediate: сбой одной корутины (sweep) не должен валить весь
    // scope — иначе сканер тихо «умирал» бы навсегда. immediate избавляет от лишнего
    // ре-диспатча, когда мы и так на главном потоке.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sweepJob: Job? = null
    private var isScanning = false

    // Намерение приложения сканировать: остаётся true, даже когда сканирование
    // временно невозможно (Bluetooth выключен), чтобы автоматически возобновить его.
    private var wantScan = false
    private var receiverRegistered = false

    private val visibleBeacons = ConcurrentHashMap<String, BeaconInfo>()

    // Сканируем только маяки из белого списка (UUID достопримечательностей с бекенда).
    // @Volatile — читается из callback-потока BLE, пишется из главного потока.
    @Volatile
    private var allowedUuids: Set<String> = emptySet()

    private val bluetoothAdapter by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private val bluetoothLeScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(SCAN_MODE)
        .build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.rssi < MIN_RSSI) return

            // Берём первый Service UUID из белого списка (устройство могло
            // рекламировать несколько UUID — нас интересует только нужный)
            val uuid = result.scanRecord?.serviceUuids
                ?.map { it.uuid.toString().uppercase() }
                ?.firstOrNull { it in allowedUuids }
                ?: return

            visibleBeacons[uuid] = BeaconInfo(
                address   = result.device.address,
                uuid      = uuid,
                rssi      = result.rssi,
                lastSeenMs = System.currentTimeMillis()
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed, error code: $errorCode")
            // Сканирование не стартовало — освобождаем ресурсы, иначе цикл sweep
            // останется висеть навсегда. wantScan сохраняем для авто-возобновления.
            stopScanningInternal()
            _scanState.value = ScanState.Error("Сканирование не удалось (код $errorCode)")
        }
    }

    // Следим за включением/выключением Bluetooth во время работы приложения
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    if (wantScan && !isScanning) {
                        Log.d(TAG, "Bluetooth включён — возобновляем сканирование")
                        attemptStartScan()
                    }
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (isScanning || wantScan) {
                        Log.w(TAG, "Bluetooth выключен во время работы")
                        stopScanningInternal()
                        _scanState.value = ScanState.Error("Включите Bluetooth")
                    }
                }
            }
        }
    }

    private fun startSweep() {
        sweepJob?.cancel()
        sweepJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                visibleBeacons.entries.removeIf { now - it.value.lastSeenMs > BEACON_TIMEOUT_MS }
                _detectedBeacon.value = visibleBeacons.values.maxByOrNull { it.rssi }
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    override fun startScan(allowedUuids: Set<String>) {
        // Запоминаем намерение и белый список, чтобы возобновить сканирование,
        // когда Bluetooth снова включат.
        val normalized = allowedUuids.map { it.uppercase() }.toSet()
        // Если уже сканируем, но белый список сменился — перезапускаем скан, иначе
        // attemptStartScan() сделает ранний выход и hardware-фильтры ScanFilter
        // остались бы от старого набора UUID.
        if (isScanning && normalized != this.allowedUuids) stopScanningInternal()
        this.allowedUuids = normalized
        wantScan = true
        registerBluetoothReceiver()
        attemptStartScan()
    }

    // Пытается фактически запустить сканирование с учётом текущих условий
    // (права, состояние Bluetooth, непустой белый список).
    private fun attemptStartScan() {
        if (isScanning) return
        if (!hasBleScanPermission()) {
            Log.w(TAG, "Нет разрешения на BLE-сканирование")
            _scanState.value = ScanState.Error("Нет разрешения на сканирование Bluetooth")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "Bluetooth не поддерживается на устройстве")
            _scanState.value = ScanState.Error("Bluetooth не поддерживается на устройстве")
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth выключен")
            _scanState.value = ScanState.Error("Включите Bluetooth")
            return
        }
        val scanner = bluetoothLeScanner ?: run {
            Log.e(TAG, "BluetoothLeScanner недоступен")
            _scanState.value = ScanState.Error("BLE-сканер недоступен")
            return
        }

        // Строим фильтры по Service UUID из (уже нормализованного) белого списка
        val filters = allowedUuids.mapNotNull { uuid ->
            runCatching {
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(uuid))
                    .build()
            }.getOrElse {
                Log.w(TAG, "Некорректный UUID в белом списке: $uuid")
                null
            }
        }

        if (filters.isEmpty()) {
            Log.w(TAG, "Белый список пуст — сканирование не запущено")
            _scanState.value = ScanState.Error("Список достопримечательностей пуст")
            return
        }

        try {
            scanner.startScan(filters, scanSettings, scanCallback)
        } catch (e: SecurityException) {
            // Разрешение могло быть отозвано между проверкой и вызовом
            Log.e(TAG, "Нет разрешения на запуск сканирования", e)
            _scanState.value = ScanState.Error("Нет разрешения на сканирование Bluetooth")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить сканирование", e)
            _scanState.value = ScanState.Error(e.message ?: "Ошибка запуска сканирования")
            return
        }

        startSweep()
        isScanning = true
        _scanState.value = ScanState.Scanning
        Log.d(TAG, "Сканирование запущено (${filters.size} маяков в белом списке)")
    }

    override fun stopScan() {
        wantScan = false
        if (isScanning) Log.d(TAG, "Сканирование остановлено")
        stopScanningInternal()
        _scanState.value = ScanState.Idle
    }

    // Останавливает активное сканирование и освобождает ресурсы, НЕ сбрасывая
    // намерение wantScan (используется при выключении Bluetooth и сбое сканирования).
    private fun stopScanningInternal() {
        if (isScanning) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                Log.w(TAG, "Нет разрешения на остановку сканирования", e)
            }
        }
        sweepJob?.cancel()
        sweepJob = null
        visibleBeacons.clear()
        _detectedBeacon.value = null
        isScanning = false
    }

    // Полное освобождение ресурсов — вызывать из onDestroy
    override fun release() {
        stopScan()
        unregisterBluetoothReceiver()
        scope.cancel()
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return
        // 2-аргументный registerReceiver корректен: ACTION_STATE_CHANGED — защищённый
        // системный бродкаст, на него не распространяется требование RECEIVER_EXPORTED
        // (Android 14+). Не «чинить» добавлением флага.
        context.applicationContext.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        receiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!receiverRegistered) return
        runCatching { context.applicationContext.unregisterReceiver(bluetoothStateReceiver) }
        receiverRegistered = false
    }

    // На Android 12+ нужен BLUETOOTH_SCAN; на 11 и ниже BLE-скан реально требует
    // ACCESS_FINE_LOCATION (его же запрашивает MainActivity на этих версиях).
    // Без этой проверки путь авто-возобновления (STATE_ON) мог бы стартовать скан
    // без локации — система молча вернёт 0 результатов, и UI зависнет на «Поиск».
    private fun hasBleScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        else hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
