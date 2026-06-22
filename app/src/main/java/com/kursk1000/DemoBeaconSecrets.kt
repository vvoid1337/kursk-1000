package com.kursk1000

/**
 * Демо-секреты меток на время разработки (нет боевого провижининга и реального железа).
 *
 * Это НЕ боевое хранение ключей, а placeholder'ы для демонстрации механизма защиты (TZ
 * Вариант А): в продакшене секреты провижатся по TLS в Android Keystore как неэкспортируемые
 * HMAC-ключи (см. [KeystoreBeaconAuthKeyProvider]).
 *
 * Единый источник секретов для ОБЕИХ сторон демо:
 *  - гид ([AppContainer] → [BeaconVerifier]) проверяет коду;
 *  - приложение-эмулятор ([BeaconEmulatorViewModel]) генерирует тот же код.
 * Схема симметричная, поэтому набор обязан совпадать байт-в-байт — отсюда один объект, а не
 * две копии. UUID соответствуют контенту бекенда (по файлу на достопримечательность в
 * kursk1000-api).
 */
internal object DemoBeaconSecrets {
    val secrets: Map<String, ByteArray> = mapOf(
        "A1B2C3D4-E5F6-7890-ABCD-EF1234567890" to "demo-secret-znamensky".toByteArray(),
        "B2C3D4E5-F6A7-8901-BCDE-F12345678901" to "demo-secret-krasnaya".toByteArray(),
        "C3D4E5F6-A7B8-9012-CDEF-123456789012" to "demo-secret-krepost".toByteArray(),
    )
}
