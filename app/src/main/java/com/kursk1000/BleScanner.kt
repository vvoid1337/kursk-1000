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
    // Код подлинности из BLE Service Data (hex) или null, если метка его не вещает.
    // Проверку делает BeaconVerifier во ViewModel - сканер секретов не знает.
    val authData: String? = null,
    // Ключуем по MAC, а не UUID: реальная и поддельная метка на одном UUID - две разные записи.
    val deviceAddress: String = "",
)

// Состояние BLE-сканера для UI
sealed class ScanState {
    data object Idle : ScanState()
    data object Scanning : ScanState()
    data class Error(val message: String) : ScanState()
}

/**
 * Интерфейс сканера - чтобы в тестах подставлять фейк без реального Bluetooth.
 * Боевая реализация - [RealBleScanner].
 */
interface BleScanner {
    // Список физических устройств в эфире (по одному на MAC). Подделки отсеивает ViewModel.
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
        // BALANCED вместо LOW_LATENCY: посетитель подходит пешком, батарею незачем жечь
        private const val SCAN_MODE = ScanSettings.SCAN_MODE_BALANCED
        // Android 7+ режет частые старты BLE-скана — держим кулдаун между ними
        private const val SCAN_COOLDOWN_MS = 5_000L
    }

    private val _visibleBeacons = MutableStateFlow<List<BeaconInfo>>(emptyList())
    override val visibleBeacons: StateFlow<List<BeaconInfo>> = _visibleBeacons.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // SupervisorJob: сбой sweep-корутины не валит весь scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sweepJob: Job? = null
    private var isScanning = false
    private var lastStopMs = 0L
    private var cooldownJob: Job? = null

    // true пока хотим сканировать - чтобы возобновить после включения Bluetooth
    private var wantScan = false
    private var receiverRegistered = false

    // Ключ - MAC устройства: реальная и поддельная метка на одном UUID живут отдельно.
    // Дедуп по UUID и проверка кода - задача ViewModel.
    private val beaconsByAddress = ConcurrentHashMap<String, BeaconInfo>()

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

            val uuid = result.scanRecord?.serviceUuids
                ?.map { it.uuid.toString().uppercase() }
                ?.firstOrNull { it in allowedUuids }
                ?: return

            // Код едет в Service Data под тем же UUID. null — метка код не вещает.
            val authData = result.scanRecord
                ?.getServiceData(ParcelUuid.fromString(uuid))
                ?.let { BeaconCode.toHex(it) }

            // Fallback на UUID если адрес пустой/placeholder (Android 12+)
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
            stopScanningInternal()
            _scanState.value = ScanState.Error(context.getString(R.string.scan_failed, errorCode))
        }
    }

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
        val normalized = allowedUuids.map { it.uppercase() }.toSet()
        // UUID-список сменился - перезапускаем, иначе hardware-фильтры останутся старыми
        if (isScanning && normalized != this.allowedUuids) stopScanningInternal()
        this.allowedUuids = normalized
        wantScan = true
        registerBluetoothReceiver()
        attemptStartScan()
    }

    private fun attemptStartScan() {
        if (isScanning) return

        val sinceStop = System.currentTimeMillis() - lastStopMs
        if (sinceStop < SCAN_COOLDOWN_MS) {
            // кд не вышел - откладываем старт
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

    // Останавливает скан без сброса wantScan - используется при выключении BT и сбоях
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

    override fun release() {
        stopScan()
        unregisterBluetoothReceiver()
        cooldownJob?.cancel()
        scope.cancel()
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return
        // ACTION_STATE_CHANGED - защищённый системный бродкаст, флаг RECEIVER_EXPORTED не нужен
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

    // Android 12+: BLUETOOTH_SCAN
    // ниже: ACCESS_FINE_LOCATION (его же просит MainActivity)
    private fun hasBleScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        else hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
