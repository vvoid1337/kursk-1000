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
    val uuid: String,
    val rssi: Int,
    val lastSeenMs: Long = System.currentTimeMillis(),
    // «Динамический код» подлинности из BLE Service Data метки (hex, lowercase) или null,
    // если метка его не вещает (уязвимая/поддельная). Сам сканер код не проверяет — он не
    // знает секретов; проверку делает BeaconVerifier во ViewModel. Так секреты не попадают
    // в callback-поток BLE. См. TZ Вариант А (защита от спуфинга/клонирования).
    val authData: String? = null,
    // Аппаратный адрес устройства-источника. Им (а НЕ UUID) ключуется список в сканере, чтобы
    // реальная и поддельная метка, вещающие на одном Service UUID, не затирали друг друга, а
    // приходили двумя записями — иначе единственный слот «дрожал» бы между валидным и поддельным
    // кодом каждый свип. Для записей из тестов/предпросмотра по умолчанию пуст.
    val deviceAddress: String = "",
)

// Состояние BLE-сканера для отображения в UI
sealed class ScanState {
    data object Idle : ScanState()                    // сканирование не запущено
    data object Scanning : ScanState()                // активный поиск маяков
    data class Error(val message: String) : ScanState()  // ошибка (Bluetooth выключен, нет прав и т.п.)
}

/**
 * Контракт BLE-сканера, от которого зависит ViewModel. Вынесен в интерфейс, чтобы в
 * тестах подставлять фейк (эмитит в [visibleBeacons]/[scanState]) и проверять
 * залипающее состояние и гейтинг скана без реального Bluetooth и устройства.
 * Боевая реализация — [RealBleScanner].
 */
interface BleScanner {
    // Сырой список физических устройств в эфире (по одному на MAC-адрес, поэтому реальная и
    // поддельная метка на ОДНОМ UUID приходят разными записями). Сканер не знает секретов и не
    // фильтрует подделки — проверку подлинности и дедуп по UUID делает ViewModel (BeaconVerifier).
    val visibleBeacons: StateFlow<List<BeaconInfo>>
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
        // Android 7+ ограничивает частоту стартов BLE-скана (≈5 стартов за 30 с).
        // Кулдаун между остановкой и повторным стартом предотвращает hitting rate limiter.
        private const val SCAN_COOLDOWN_MS = 5_000L
    }

    private val _visibleBeacons = MutableStateFlow<List<BeaconInfo>>(emptyList())
    override val visibleBeacons: StateFlow<List<BeaconInfo>> = _visibleBeacons.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // SupervisorJob + Main.immediate: сбой одной корутины (sweep) не должен валить весь
    // scope — иначе сканер тихо «умирал» бы навсегда. Immediate избавляет от лишнего
    // ре-диспатча, когда мы и так на главном потоке.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sweepJob: Job? = null
    private var isScanning = false
    // Кулдаун после остановки скана: Android 7+ дросселит частые stopScan/startScan.
    private var lastStopMs = 0L
    private var cooldownJob: Job? = null

    // Намерение приложения сканировать: остаётся true, даже когда сканирование
    // временно невозможно (Bluetooth выключен), чтобы автоматически возобновить его.
    private var wantScan = false
    private var receiverRegistered = false

    // Ключ — аппаратный адрес устройства, а НЕ UUID: реальная и поддельная метка на одном
    // Service UUID должны жить отдельными записями (см. BeaconInfo.deviceAddress). Дедуп по UUID
    // и отсев подделок — задача ViewModel, у которой есть секреты для проверки кода.
    private val beaconsByAddress = ConcurrentHashMap<String, BeaconInfo>()

    // Сканируем только маяки из белого списка (UUID достопримечательностей с бекенда). @Volatile —
    // защитно: 3-арг startScan доставляет колбэк на главный Looper (как и запись здесь), т.е. де-факто
    // всё в одном потоке; флаг остаётся корректным, если в startScan однажды передадут свой Handler.
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

            // Код подлинности едет в Service Data под тем же Service UUID (эмулятор кладёт
            // его в scan response — активный скан его забирает, getServiceData сливает adv +
            // scan response). null, если метка код не вещает. Проверку делает BeaconVerifier.
            val authData = result.scanRecord
                ?.getServiceData(ParcelUuid.fromString(uuid))
                ?.let { BeaconCode.toHex(it) }

            // Ключуем по адресу устройства: реальная и поддельная метка на одном Service UUID
            // попадают в РАЗНЫЕ записи и не затирают друг друга. ViewModel поверх этого списка
            // выберет подлинную (BeaconVerifier) и схлопнет дубли по UUID.
            // Адрес может быть "02:00:00:00:00:00" (Android 12+ placeholder), или строкой
            // нулевой длины на некоторых ПЗУ — OK, он всё равно нужен только чтобы различать
            // устройства, а не как идентификатор; упадём до UUID, если адрес пуст/null.
            val address = result.device.address?.takeIf { it.isNotBlank() } ?: uuid
            beaconsByAddress[address] = BeaconInfo(
                uuid       = uuid,
                rssi       = result.rssi,
                lastSeenMs = System.currentTimeMillis(),
                authData   = authData,
                deviceAddress = address,
            )
            publishVisibleBeacons()
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed, error code: $errorCode")
            // Сканирование не стартовало — освобождаем ресурсы, иначе цикл sweep
            // останется висеть навсегда. wantScan сохраняем для авто-возобновления.
            stopScanningInternal()
            _scanState.value = ScanState.Error(context.getString(R.string.scan_failed, errorCode))
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
                        // Берём Context класса (всегда есть), а не nullable-аргумент onReceive.
                        _scanState.value =
                            ScanState.Error(this@RealBleScanner.context.getString(R.string.enable_bluetooth))
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
                beaconsByAddress.entries.removeIf { now - it.value.lastSeenMs > BEACON_TIMEOUT_MS }
                publishVisibleBeacons()
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    private fun publishVisibleBeacons() {
        _visibleBeacons.value = beaconsByAddress.values.sortedByDescending { it.rssi }
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

        val sinceStop = System.currentTimeMillis() - lastStopMs
        if (sinceStop < SCAN_COOLDOWN_MS) {
            // Кулдаун после остановки: планируем отложенный старт. Если за время ожидания
            // придёт ещё один startScan — cancel старого job и запланируем новый (свежий
            // cooldown от последней остановки не нужен — lastStopMs не менялся).
            if (cooldownJob?.isActive != true) {
                cooldownJob = scope.launch {
                    delay(SCAN_COOLDOWN_MS - sinceStop)
                    attemptStartScan()
                }
            }
            return
        }
        if (!hasBleScanPermission()) {
            Log.w(TAG, "Нет разрешения на BLE-сканирование")
            _scanState.value = ScanState.Error(context.getString(R.string.ble_scan_permission_missing))
            return
        }
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "Bluetooth не поддерживается на устройстве")
            _scanState.value = ScanState.Error(context.getString(R.string.bluetooth_unsupported))
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth выключен")
            _scanState.value = ScanState.Error(context.getString(R.string.enable_bluetooth))
            return
        }
        val scanner = bluetoothLeScanner ?: run {
            Log.e(TAG, "BluetoothLeScanner недоступен")
            _scanState.value = ScanState.Error(context.getString(R.string.ble_scanner_unavailable))
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
            _scanState.value = ScanState.Error(context.getString(R.string.landmark_list_empty))
            return
        }

        try {
            scanner.startScan(filters, scanSettings, scanCallback)
        } catch (e: SecurityException) {
            // Разрешение могло быть отозвано между проверкой и вызовом
            Log.e(TAG, "Нет разрешения на запуск сканирования", e)
            _scanState.value = ScanState.Error(context.getString(R.string.ble_scan_permission_missing))
            return
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить сканирование", e)
            _scanState.value = ScanState.Error(e.message ?: context.getString(R.string.scan_start_error))
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
            lastStopMs = System.currentTimeMillis()
        }
        cooldownJob?.cancel()
        cooldownJob = null
        sweepJob?.cancel()
        sweepJob = null
        beaconsByAddress.clear()
        _visibleBeacons.value = emptyList()
        isScanning = false
    }

    // Полное освобождение ресурсов — вызывать из onDestroy
    override fun release() {
        stopScan()
        unregisterBluetoothReceiver()
        cooldownJob?.cancel()
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
