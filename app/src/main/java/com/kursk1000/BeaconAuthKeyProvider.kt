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
 * Приёмник секретов меток: импортирует их в защищённое хранилище. Отделён от [BeaconAuthKeyProvider],
 * чтобы data-слой ([OfflineFirstLandmarkRepository]) зависел от узкого «куда положить секрет», а не
 * от Android Keystore напрямую — так провижининг подменяется фейком в JVM-тестах.
 *
 * Боевая реализация — [KeystoreBeaconAuthKeyProvider] (она же и проверяет коды): секрет приходит с
 * бекенда вместе со списком меток и кладётся в Keystore как неэкспортируемый HMAC-ключ.
 */
interface BeaconSecretProvisioner {
    fun provision(secrets: Map<String, ByteArray>)
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
 * Боевая реализация «безопасного хранения ключей» (требование ТЗ: секреты не хранить в коде в
 * открытом виде). Секрет метки приходит с бекенда вместе со списком меток (см.
 * [RemoteLandmarkDataSource]) и импортируется в Android Keystore как НЕэкспортируемый HMAC-ключ:
 * дальше HMAC считается самим Keystore, а сырьё ключа из хранилища достать нельзя (даже при
 * компрометации файлов приложения). Ключ переживает перезапуск, поэтому после первой синхронизации
 * проверка работает офлайн.
 *
 * Тот же экземпляр и проверяет коды ([macFor]), и принимает секреты ([provision]) — гид и
 * приложение-эмулятор пользуются одним Keystore процесса.
 *
 * Не покрыт JVM-тестами — это Android-only путь (AndroidKeyStore). Логика проверки кода
 * тестируется против [FakeBeaconAuthKeyProvider]; здесь — только хранение.
 */
class KeystoreBeaconAuthKeyProvider : BeaconAuthKeyProvider, BeaconSecretProvisioner {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun provision(secrets: Map<String, ByteArray>) {
        secrets.forEach { (uuid, secret) -> provision(uuid, secret) }
    }

    /** Импортировать секрет одной метки в Keystore как неэкспортируемый HMAC-ключ (идемпотентно). */
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
