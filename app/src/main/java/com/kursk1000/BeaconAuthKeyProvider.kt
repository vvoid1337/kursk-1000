package com.kursk1000

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Log
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** источник секрета для проверки меток.
секрет отделён от модели/кэша чтобы кэш не позволял подделывать коды.
гид получает готовый mac (инициализированный секретом),
а не сами байты. возвращает null, если для uuid секрет отсутствует. */
interface BeaconAuthKeyProvider {
    fun macFor(uuid: String): Mac?
}

/** приёмник секретов меток импортирует их в защищённое хранилище. отделён от BeaconAuthKeyProvider,
чтобы data-слой не зависел от Android Keystore напрямую.
боевая реализация KeystoreBeaconAuthKeyProvider получает секреты с бекенда
и сохраняет как неэкспортируемые HMAC-ключи в Keystore.*/
interface BeaconSecretProvisioner {
    fun provision(secrets: Map<String, ByteArray>)
}

/** Секрет метки получается с бекенда и импортируется в Android Keystore
как неэкспортируемый HMAC-ключ. HMAC вычисляется самим Keystore,
сырой ключ недоступен даже при компрометации. Ключ сохраняется между перезапусками,
поэтому после первой синхронизации проверка работает офлайн.
Один экземпляр используется и для проверки кодов, и для приёма секретов.*/
class KeystoreBeaconAuthKeyProvider : BeaconAuthKeyProvider, BeaconSecretProvisioner {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun provision(secrets: Map<String, ByteArray>) {
        secrets.forEach { (uuid, secret) -> provision(uuid, secret) }
    }

    /** Импортировать секрет одной метки в Keystore как неэкспортируемый HMAC-ключ */
    fun provision(uuid: String, secret: ByteArray) {
        runCatching {
            keyStore.setEntry(
                aliasFor(uuid),
                KeyStore.SecretKeyEntry(SecretKeySpec(secret, HMAC_ALG)),
                KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build(),
            )
        }.onFailure { Log.e(TAG, "Не удалось импортировать секрет метки в Keystore", it) }
    }

    override fun macFor(uuid: String): Mac? = runCatching {
        val key = keyStore.getKey(aliasFor(uuid), null) as? SecretKey ?: return null
        Mac.getInstance(HMAC_ALG).apply { init(key) }
    }.getOrNull()

    private fun aliasFor(uuid: String): String = ALIAS_PREFIX + uuid.uppercase()

    private companion object {
        const val TAG = "BeaconKeystore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val HMAC_ALG = "HmacSHA256"
        const val ALIAS_PREFIX = "beacon_secret_"
    }
}
