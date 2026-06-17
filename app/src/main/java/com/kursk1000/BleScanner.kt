package com.kursk1000

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BeaconInfo(
    val address: String,
    val uuid: String,
    val rssi: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
)

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        private const val BEACON_TIMEOUT_MS = 3_000L
        private const val SWEEP_INTERVAL_MS = 1_000L
        private const val MIN_RSSI = -75
    }

    private val _detectedBeacon = MutableStateFlow<BeaconInfo?>(null)
    val detectedBeacon: StateFlow<BeaconInfo?> = _detectedBeacon.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var sweepJob: Job? = null
    private var isScanning = false

    // BLE callback работает на фоновом потоке, sweep — на Main.
    // ConcurrentHashMap обеспечивает безопасный доступ из обоих.
    private val visibleBeacons = ConcurrentHashMap<String, BeaconInfo>()

    private val bluetoothAdapter by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private val bluetoothLeScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    // Фильтры берём из единого источника — BeaconUuids.ALL
    private val scanFilters: List<ScanFilter> = BeaconUuids.ALL.map { uuid ->
        ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(uuid)))
            .build()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.rssi < MIN_RSSI) return

            val uuid = result.scanRecord?.serviceUuids
                ?.firstOrNull()?.uuid?.toString()?.uppercase()
                ?: return

            visibleBeacons[uuid] = BeaconInfo(
                address = result.device.address,
                uuid = uuid,
                rssi = result.rssi,
                lastSeenMs = System.currentTimeMillis()
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed, error code: $errorCode")
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

    fun startScan() {
        if (isScanning) return
        if (!hasBleScanPermission()) {
            Log.w(TAG, "Нет разрешения на BLE-сканирование")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth выключен")
            return
        }

        val scanner = bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner недоступен")
            return
        }

        scanner.startScan(scanFilters, scanSettings, scanCallback)
        startSweep()
        isScanning = true
        Log.d(TAG, "Сканирование запущено")
    }

    fun stopScan() {
        if (!isScanning) return

        bluetoothLeScanner?.stopScan(scanCallback)
        sweepJob?.cancel()
        sweepJob = null
        visibleBeacons.clear()
        _detectedBeacon.value = null
        isScanning = false
        Log.d(TAG, "Сканирование остановлено")
    }

    private fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // На Android 11 и ниже BLUETOOTH_SCAN не существует,
            // достаточно ACCESS_FINE_LOCATION (проверяется отдельно в UI)
            true
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}