package com.kursk1000

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** вердикт проверки подлинности метки. */
enum class BeaconAuthState {
    /** Код метки совпал с ожидаемым в актуальном временном окне - метка подлинная. */
    AUTHENTIC,

    /** Кода нет / он не совпал / протух / это повтор (replay) - метке доверять нельзя. */
    UNVERIFIED,
}

/**
Проверка кода метки: для UUID берём секрет, вычисляем ожидаемый HMAC для текущего окна +-window
(терпимость к дрейфу часов) и сравниваем с полученным кодом за константное время.
Анти-replay: запоминаем последний принятый счётчик для каждого UUID.
Тот же счётчик (повтор в пределах 30‑секундного окна) пропускаем как легитимный,
строго меньший отклоняем. Карта счётчиков живёт в ViewModel весь сеанс и не сбрасывается
при закрытии карточки, чтобы не открывать окно для повторов.*/
class BeaconVerifier(
    private val keyProvider: BeaconAuthKeyProvider,
    private val window: Int = 1,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val lastCounterByUuid = ConcurrentHashMap<String, Long>()

    fun verify(uuid: String, authData: String?): BeaconAuthState {
        val received = BeaconCode.codeFromPayload(BeaconCode.fromHex(authData)) ?: return BeaconAuthState.UNVERIFIED
        val mac = keyProvider.macFor(uuid) ?: return BeaconAuthState.UNVERIFIED

        val key = uuid.uppercase()
        val current = BeaconCode.counterAt(nowMs())

        // идём от свежих счётчиков к старым: совпавший наибольший счетчик и запишем как последний.
        for (counter in (current + window) downTo (current - window)) {
            val expected = BeaconCode.code(mac, counter)
            if (!MessageDigest.isEqual(expected, received)) continue

            val last = lastCounterByUuid[key]
            // строго устаревший счётчик - записанный ранее и переигранный пакет
            if (last != null && counter < last) return BeaconAuthState.UNVERIFIED
            lastCounterByUuid[key] = counter
            return BeaconAuthState.AUTHENTIC
        }
        return BeaconAuthState.UNVERIFIED
    }
}
