package com.kursk1000

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Вердикт проверки подлинности метки. */
enum class BeaconAuthState {
    /** Код метки совпал с ожидаемым в актуальном временном окне — метка подлинная. */
    AUTHENTIC,

    /** Кода нет / он не совпал / протух / это повтор (replay) — метке доверять нельзя. */
    UNVERIFIED,
}

/**
 * Проверяет «динамический код» метки (TZ Вариант А). Чистый JVM — секреты берёт из
 * [BeaconAuthKeyProvider], криптопримитивы из `javax.crypto`, поэтому полностью покрыт
 * юнит-тестами без устройства.
 *
 * Алгоритм: для UUID берём его секрет, считаем ожидаемый HMAC на счётчиках текущего окна
 * ±[window] (терпимость к дрейфу часов гида и метки) и сравниваем с пришедшим кодом в
 * константное время.
 *
 * Анти-replay: помним последний принятый счётчик на UUID. Тот же счётчик (повторный подход
 * к метке в пределах одного 30-сек окна) принимаем — это легитимно; СТРОГО меньший счётчик
 * отклоняем как повтор записанного ранее пакета. Карта живёт весь сеанс (инстансом владеет
 * ViewModel) и НЕ сбрасывается при закрытии карточки — иначе сброс заново открывал бы окно для
 * переигрывания недавнего пакета, а легальный повторный подход и так проходит (тот же/новее
 * counter принимается). [reset] оставлен для полного teardown/тестов.
 */
class BeaconVerifier(
    private val keyProvider: BeaconAuthKeyProvider,
    private val window: Int = 1,
    // Источник времени вынесен для детерминизма тестов; в бою — системные часы.
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val lastCounterByUuid = ConcurrentHashMap<String, Long>()

    fun verify(uuid: String, authData: String?): BeaconAuthState {
        val received = BeaconCode.codeFromPayload(BeaconCode.fromHex(authData)) ?: return BeaconAuthState.UNVERIFIED
        val mac = keyProvider.macFor(uuid) ?: return BeaconAuthState.UNVERIFIED

        val key = uuid.uppercase()
        val current = BeaconCode.counterAt(nowMs())

        // Идём от свежих счётчиков к старым: совпавший наибольший counter и запишем как последний.
        for (counter in (current + window) downTo (current - window)) {
            val expected = BeaconCode.code(mac, counter)
            if (!MessageDigest.isEqual(expected, received)) continue

            val last = lastCounterByUuid[key]
            // Строго устаревший счётчик — записанный ранее и переигранный пакет.
            if (last != null && counter < last) return BeaconAuthState.UNVERIFIED
            lastCounterByUuid[key] = counter
            return BeaconAuthState.AUTHENTIC
        }
        return BeaconAuthState.UNVERIFIED
    }

    /** Полностью забыть историю принятых счётчиков. Для явного teardown/тестов; в обычном потоке
     *  не вызывается (история живёт весь сеанс ради replay-стойкости). */
    fun reset() = lastCounterByUuid.clear()
}
