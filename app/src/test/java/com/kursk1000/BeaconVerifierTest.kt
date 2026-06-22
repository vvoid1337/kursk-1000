package com.kursk1000

import org.junit.Assert.assertEquals
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Test

/**
 * Контракт защиты от спуфинга/клонирования (TZ Вариант А). Валидные коды генерируются тем же
 * [BeaconCode], что использует и метка/эмулятор, — проверяем сквозной код-путь без устройства.
 */
class BeaconVerifierTest {

    private val uuid = "A1B2C3D4-E5F6-7890-ABCD-EF1234567890"
    private val secret = "demo-secret".toByteArray()
    private val now = 1_700_000_000_000L
    private val keyProvider = FakeBeaconAuthKeyProvider(mapOf(uuid to secret))

    private fun verifier(nowMs: Long = now) = BeaconVerifier(keyProvider, window = 1, nowMs = { nowMs })

    /** hex-пакет Service Data для счётчика текущего окна + [delta]. */
    private fun authData(deltaSteps: Long = 0, atMs: Long = now, key: ByteArray = secret): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return BeaconCode.toHex(BeaconCode.payload(mac, BeaconCode.counterAt(atMs) + deltaSteps))
    }

    @Test
    fun validCurrentWindow_authentic() {
        assertEquals(BeaconAuthState.AUTHENTIC, verifier().verify(uuid, authData()))
    }

    @Test
    fun oneStepSkew_authentic() {
        // Часы метки на шаг впереди/позади — окно ±1 должно простить.
        assertEquals(BeaconAuthState.AUTHENTIC, verifier().verify(uuid, authData(deltaSteps = -1)))
        assertEquals(BeaconAuthState.AUTHENTIC, verifier().verify(uuid, authData(deltaSteps = +1)))
    }

    @Test
    fun staleCodeBeyondWindow_unverified() {
        // Перехваченный код, протухший более чем на шаг, — replay-стойкость.
        assertEquals(BeaconAuthState.UNVERIFIED, verifier().verify(uuid, authData(deltaSteps = -3)))
    }

    @Test
    fun tamperedCode_unverified() {
        val good = authData()
        // Портим последний байт hex.
        val tampered = good.dropLast(1) + if (good.last() == '0') '1' else '0'
        assertEquals(BeaconAuthState.UNVERIFIED, verifier().verify(uuid, tampered))
    }

    @Test
    fun missingOrMalformedPayload_unverified() {
        val v = verifier()
        assertEquals(BeaconAuthState.UNVERIFIED, v.verify(uuid, null))
        assertEquals(BeaconAuthState.UNVERIFIED, v.verify(uuid, ""))
        assertEquals(BeaconAuthState.UNVERIFIED, v.verify(uuid, "zz")) // не-hex
        assertEquals(BeaconAuthState.UNVERIFIED, v.verify(uuid, "0102")) // короткий пакет
    }

    @Test
    fun unknownUuid_unverified() {
        assertEquals(
            BeaconAuthState.UNVERIFIED,
            verifier().verify("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF", authData()),
        )
    }

    @Test
    fun wrongSecret_unverified() {
        val forged = authData(key = "other-secret".toByteArray())
        assertEquals(BeaconAuthState.UNVERIFIED, verifier().verify(uuid, forged))
    }

    @Test
    fun replaySameCounterAccepted_butStrictlyOlderRejected() {
        val v = verifier()
        // Текущее окно принимается.
        assertEquals(BeaconAuthState.AUTHENTIC, v.verify(uuid, authData()))
        // Тот же счётчик ещё раз (повторный подход к метке за те же 30 c) — легитимно.
        assertEquals(BeaconAuthState.AUTHENTIC, v.verify(uuid, authData()))
        // Записанный ранее пакет (счётчик строго меньше принятого) — отвергаем как replay,
        // хотя он ещё попадает в окно ±1.
        assertEquals(BeaconAuthState.UNVERIFIED, v.verify(uuid, authData(deltaSteps = -1)))
    }

    @Test
    fun resetClearsReplayHistory() {
        val v = verifier()
        v.verify(uuid, authData())
        v.reset()
        // После reset более старый счётчик снова принимается (история забыта).
        assertEquals(BeaconAuthState.AUTHENTIC, v.verify(uuid, authData(deltaSteps = -1)))
    }
}
