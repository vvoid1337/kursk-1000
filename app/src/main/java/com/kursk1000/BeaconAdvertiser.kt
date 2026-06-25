package com.kursk1000

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/** обёртка над BluetoothLeAdvertiser для эмулятора метки (метка вещает, гид сканирует).
готовый Service Data приходит из BeaconEmulatorViewModel. UUID передаётся в основном рекламном пакете,
а код подлинности — в Service Data через scan response (так умещается 128-битный UUID + данные).
если serviceData == null — метка без кода (клон/спуфер): гид обнаружит, но проверку не пройдёт.*/
class BeaconAdvertiser(context: Context) {

    private val appContext: Context = context.applicationContext

    // геттер, а не lazy: bluetoothLeAdvertiser становится null при выключенном Bluetooth и
    // снова доступен при включении — кешировать его нельзя.
    private val advertiser: BluetoothLeAdvertiser?
        get() {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter?.bluetoothLeAdvertiser
        }

    private var activeCallback: AdvertiseCallback? = null

    /** поддерживает ли BLE вещание и включен ли bluetooth*/
    val isSupported: Boolean
        get() = advertiser != null

    // запуск/перезапуск вещания: предыдущее останавливается,
    // результат (успех/ошибка) приходит асинхронно в onResult.
    // повторные вызовы безопасны - используются для ротации кода.
    fun start(
        uuid: String,
        serviceData: ByteArray?,
        txPowerLevel: Int,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val adv = advertiser ?: run {
            onResult(Result.failure(IllegalStateException("BLE-вещание недоступно")))
            return
        }
        val parcelUuid = runCatching { ParcelUuid.fromString(uuid) }.getOrElse {
            onResult(Result.failure(it))
            return
        }

        val settings = AdvertiseSettings.Builder()
            // LOW_LATENCY: для демо
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(txPowerLevel)
            .setConnectable(false) // только broadcast - к метке не подключаются
            .setTimeout(0)         // вещаем до явного stop
            .build()

        // основной пакет: имя устройства выключаем (съело бы бюджет 31 байта), оставляем UUID.
        val primary = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(parcelUuid)
            .build()

        // код едет в scan response. В «уязвимом» режиме его нет вовсе.
        val scanResponse = serviceData?.let {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceData(parcelUuid, it)
                .build()
        }

        stopInternal(adv)

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                onResult(Result.success(Unit))
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Старт вещания не удался, код $errorCode")
                onResult(Result.failure(IllegalStateException("Ошибка вещания (код $errorCode)")))
            }
        }

        try {
            if (scanResponse != null) {
                adv.startAdvertising(settings, primary, scanResponse, callback)
            } else {
                adv.startAdvertising(settings, primary, callback)
            }
            activeCallback = callback
        } catch (e: SecurityException) {
            // на случай отзыва между проверкой и вызовом
            Log.e(TAG, "Нет разрешения на BLE-вещание", e)
            onResult(Result.failure(e))
        }
    }

    //остановка бродкаста
    fun stop() {
        advertiser?.let { stopInternal(it) }
    }

    private fun stopInternal(adv: BluetoothLeAdvertiser) {
        activeCallback?.let { cb ->
            try {
                adv.stopAdvertising(cb)
            } catch (e: SecurityException) {
                // разрешение на BLE-вещание могли отозвать - остановка всё равно безопасна
                Log.e(TAG, "Нет разрешения на остановку BLE-вещания", e)
            }
        }
        activeCallback = null
    }

    private companion object {
        const val TAG = "BeaconAdvertiser"
    }
}
