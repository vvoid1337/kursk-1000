package com.kursk1000

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Log
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Источник секретного материала для проверки подлинности меток (TZ Вариант А).
 *
 * Сем намеренно отделён от доменной модели/кэша: симметричный секрет НЕ кладётся в Room
 * и не ездит вместе с контентом карточки — иначе любой, кто вытащит кэш, сможет
 * подделывать коды. Гид получает не сами байты ключа, а готовый к работе [Mac]
 * (инициализированный секретом), чтобы боевая реализация считала HMAC ключом из Android
 * Keystore, который физически нельзя извлечь.
 *
 * Возвращает `null`, если для [uuid] секрета нет (метка вне доверенного набора).
 */
interface BeaconAuthKeyProvider {
    fun macFor(uuid: String): Mac?
}

/**
 * Тестовый/демо-провайдер: секреты держит в памяти. Используется в JVM-тестах и как
 * временный источник, пока нет приложения-эмулятора и боевого провижининга. Реализован на
 * чистом JVM (`SecretKeySpec`), поэтому работает в юнит-тестах без устройства.
 *
 * Это сознательно НЕ боевой путь хранения ключей — для демонстрации механизма достаточно,
 * для продакшена см. [KeystoreBeaconAuthKeyProvider].
 */
class FakeBeaconAuthKeyProvider(secrets: Map<String, ByteArray>) : BeaconAuthKeyProvider {

    // Ключи нормализуем по верхнему регистру: UUID в эфире и в белом списке могут отличаться
    // регистром, а это один и тот же идентификатор.
    private val secrets: Map<String, ByteArray> =
        secrets.mapKeys { it.key.uppercase() }

    override fun macFor(uuid: String): Mac? {
        val secret = secrets[uuid.uppercase()] ?: return null
        return Mac.getInstance(HMAC_ALG).apply { init(SecretKeySpec(secret, HMAC_ALG)) }
    }

    private companion object {
        const val HMAC_ALG = "HmacSHA256"
    }
}

/**
 * Боевая реализация «безопасного хранения ключей» (требование ТЗ: секреты не хранить в
 * открытом виде). Секрет метки провижится по TLS один раз и импортируется в Android Keystore
 * как НЕэкспортируемый HMAC-ключ: дальше HMAC считается самим Keystore, а сырьё ключа из
 * хранилища достать нельзя (даже при компрометации файлов приложения).
 *
 * Не покрыт JVM-тестами — это Android-only путь (AndroidKeyStore). Логика проверки кода
 * тестируется против [FakeBeaconAuthKeyProvider]; здесь — только хранение. Подключается в
 * [AppContainer] заменой одной строки, когда появится канал провижининга секретов.
 */
class KeystoreBeaconAuthKeyProvider : BeaconAuthKeyProvider {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Импортировать секрет метки в Keystore как неэкспортируемый HMAC-ключ. Вызывается из
     * провижининга (после загрузки секрета по TLS), а не из кода с захардкоженным ключом.
     */
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
