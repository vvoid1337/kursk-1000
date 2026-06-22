package com.kursk1000

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/**
 * Тонкая обёртка над [BluetoothLeAdvertiser] для приложения-эмулятора метки (сторона,
 * противоположная гиду: метка ВЕЩАЕТ, гид СКАНИРУЕТ). Крипто здесь нет — готовый пакет
 * Service Data приходит снаружи из [BeaconEmulatorViewModel] (он считает HMAC через
 * [BeaconCode]); адвертайзер только кладёт его в эфир.
 *
 * Раскладка пакета держится в синхроне с тем, что ждёт сканер гида ([RealBleScanner]):
 *  - **128-битный Service UUID** — в основном (primary) рекламном пакете: именно по нему
 *    срабатывает аппаратный `ScanFilter.setServiceUuid` гида.
 *  - **код подлинности** — в Service Data под тем же UUID, вынесенный в **scan response**:
 *    128-битный UUID + service data не влезают в один 31-байтный legacy-пакет, а активный
 *    скан гида (по умолчанию) забирает scan response, и `getServiceData` сливает adv + scan
 *    response прозрачно. Никакого extended advertising / minSdk 26 не требуется.
 *
 * [serviceData] == null → «уязвимая» метка: вещаем только UUID без кода (как клон/спуфер,
 * скопировавший лишь идентификатор) — гид найдёт её в белом списке, но проверку она не
 * пройдёт.
 */
class BeaconAdvertiser(context: Context) {

    private val appContext: Context = context.applicationContext

    // Геттер, а не lazy: bluetoothLeAdvertiser становится null при выключенном Bluetooth и
    // снова доступен при включении — кешировать его нельзя.
    private val advertiser: BluetoothLeAdvertiser?
        get() {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter?.bluetoothLeAdvertiser
        }

    private var activeCallback: AdvertiseCallback? = null

    /** Поддерживает ли устройство BLE-вещание (peripheral-режим) и включён ли Bluetooth. */
    val isSupported: Boolean
        get() = advertiser != null

    /**
     * Запустить (или перезапустить) вещание. Предыдущее вещание этого адвертайзера
     * останавливается. Результат старта приходит асинхронно в [onResult] из системного
     * колбэка (успех/ошибка). Повторные вызовы безопасны — используются для ротации кода.
     */
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
            // LOW_LATENCY: метка должна обнаруживаться быстро, расход батареи на демо не важен.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(txPowerLevel)
            .setConnectable(false) // только broadcast — к метке не подключаются
            .setTimeout(0)         // вещаем до явного stop
            .build()

        // Основной пакет: имя устройства выключаем (съело бы бюджет 31 байта), оставляем UUID.
        val primary = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(parcelUuid)
            .build()

        // Код едет в scan response (см. KDoc класса). В «уязвимом» режиме его нет вовсе.
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
            // Разрешение BLUETOOTH_ADVERTISE могло быть отозвано между проверкой и вызовом.
            Log.e(TAG, "Нет разрешения на BLE-вещание", e)
            onResult(Result.failure(e))
        }
    }

    /** Остановить вещание (идемпотентно). */
    fun stop() {
        advertiser?.let { stopInternal(it) }
    }

    private fun stopInternal(adv: BluetoothLeAdvertiser) {
        activeCallback?.let { cb -> runCatching { adv.stopAdvertising(cb) } }
        activeCallback = null
    }

    private companion object {
        const val TAG = "BeaconAdvertiser"
    }
}
