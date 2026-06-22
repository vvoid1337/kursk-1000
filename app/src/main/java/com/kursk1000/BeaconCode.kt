package com.kursk1000

import javax.crypto.Mac

/**
 * Формат и арифметика «динамического кода» метки (TZ Вариант А: защита от спуфинга и
 * клонирования по аналогии с TOTP). Единственный источник правды о формате полезной
 * нагрузки — этим же кодом будущий эмулятор будет КОДировать пакет, а гид — ДЕКОдировать,
 * поэтому модуль намеренно без Android-зависимостей (чистый JVM) и общий для обеих сторон.
 *
 * Идея: метка не вещает статический секрет. Каждые [TIME_STEP_MS] меняется счётчик времени
 * `counter = epochMillis / TIME_STEP_MS`, и метка транслирует
 * `code = HMAC-SHA256(секрет, counter)`, усечённый до [CODE_LEN] байт. Гид считает тот же
 * HMAC своим экземпляром секрета и сравнивает. Перехваченный код протухает за один-два шага
 * (replay-стойкость), а склонировать будущие коды без секрета нельзя (clone-стойкость).
 *
 * Wire-формат BLE Service Data под Service UUID метки: [VERSION][CODE_LEN байт усечённого HMAC].
 */
object BeaconCode {

    /** Шаг ротации кода. 30 c — как у TOTP: достаточно редко для дрейфа часов, достаточно
     *  часто, чтобы перехваченный код быстро протух. */
    const val TIME_STEP_MS = 30_000L

    /** Сколько байт HMAC оставляем в эфире. 8 байт = 64 бита — с запасом против подбора при
     *  30-секундном окне, и пакет помещается в scan response рядом со 128-битным UUID. */
    const val CODE_LEN = 8

    /** Версия формата пакета: позволит сменить длину/схему кода без «дня флага». */
    const val VERSION: Byte = 0x01

    /** Полная длина полезной нагрузки Service Data: байт версии + усечённый HMAC. */
    const val PAYLOAD_LEN = 1 + CODE_LEN

    /** Счётчик времени для момента [nowMs]. */
    fun counterAt(nowMs: Long): Long = nowMs / TIME_STEP_MS

    /**
     * Усечённый HMAC-SHA256 от счётчика. Принимает уже инициализированный секретом [mac]
     * (а не сырые байты ключа), чтобы боевая реализация могла считать HMAC ключом из
     * Android Keystore, который наружу не извлекается — см. [BeaconAuthKeyProvider].
     */
    fun code(mac: Mac, counter: Long): ByteArray {
        val message = ByteArray(8)
        var v = counter
        // big-endian: тот же порядок байт обязан использовать эмулятор.
        for (i in 7 downTo 0) {
            message[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return mac.doFinal(message).copyOf(CODE_LEN)
    }

    /** Собрать пакет Service Data [VERSION][code] для счётчика [counter]. Использует эмулятор. */
    fun payload(mac: Mac, counter: Long): ByteArray =
        ByteArray(PAYLOAD_LEN).also { out ->
            out[0] = VERSION
            code(mac, counter).copyInto(out, destinationOffset = 1)
        }

    /** Достать код из пакета Service Data, проверив версию и длину. `null` — пакет не наш/битый. */
    fun codeFromPayload(payload: ByteArray?): ByteArray? {
        if (payload == null || payload.size != PAYLOAD_LEN || payload[0] != VERSION) return null
        return payload.copyOfRange(1, PAYLOAD_LEN)
    }

    /** Байты → hex (lowercase). Нормализуем регистр, чтобы сравнение/логи были стабильны. */
    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            sb.append(HEX[i ushr 4])
            sb.append(HEX[i and 0x0F])
        }
        return sb.toString()
    }

    /** hex → байты. `null`, если строка пустая/нечётной длины/с не-hex символом. */
    fun fromHex(hex: String?): ByteArray? {
        if (hex == null || hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
