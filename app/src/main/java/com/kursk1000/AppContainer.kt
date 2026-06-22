package com.kursk1000

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Ручной DI-контейнер (service locator) — единственная точка сборки графа зависимостей.
 *
 * Сознательно без Hilt/Koin: граф — это пара синглтонов, питающих одну ViewModel.
 * Полноценный DI-фреймворк добавил бы KSP-процессор и церемонию ради нулевой выгоды
 * на одном экране (а ещё трение с built-in-Kotlin сборкой AGP 9). Появится второй
 * экран/фича — пересмотрим.
 *
 * Живёт в [Kursk1000App] на всё время процесса. Адрес бекенда берётся из BuildConfig,
 * собранного из build.gradle (а не из константы в коде).
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val remoteDataSource = RemoteLandmarkDataSource(
        baseUrl = BuildConfig.BASE_URL,
        ioDispatcher = ioDispatcher,
    )

    private val database: KurskDatabase = Room.databaseBuilder(
        appContext,
        KurskDatabase::class.java,
        "kursk.db",
    )
        // Кэш целиком перезаливается с бекенда, поэтому при смене схемы (версии БД) проще и
        // безопаснее пересоздать таблицы, чем писать миграции и рисковать падением на старте.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    /** Репозиторий-синглтон: список карточек кешируется и переживает перезапуск приложения. */
    val landmarkRepository: LandmarkRepository =
        OfflineFirstLandmarkRepository(database.landmarkDao(), remoteDataSource)

    /**
     * Монитор переднего плана процесса — синглтон. Инъектируется в каждую ViewModel, чтобы та
     * не звала статический ProcessLifecycleOwner напрямую (см. [ForegroundMonitor]).
     */
    val appForegroundMonitor: ForegroundMonitor = ProcessForegroundMonitor()

    /**
     * Фабрика BLE-сканера. Намеренно фабрика, а не синглтон: сканер принадлежит
     * ViewModel (создаётся в ней, освобождается в onCleared), поэтому на каждое
     * создание ViewModel выдаём свежий экземпляр. Контекст — application, без утечки.
     */
    fun createBleScanner(): BleScanner = RealBleScanner(appContext)

    /**
     * Источник секретов для проверки подлинности меток (TZ Вариант А). Сейчас подключён
     * демо-провайдер [FakeBeaconAuthKeyProvider] с известными секретами демо-меток —
     * пока нет канала провижининга. Боевой путь — заменить здесь на
     * [KeystoreBeaconAuthKeyProvider] (секреты провижатся по TLS в Android Keystore).
     *
     * Тот же провайдер переиспользует приложение-эмулятор ([BeaconEmulatorViewModel]),
     * чтобы генерировать код тем же секретом, которым гид его проверяет — поэтому val
     * публичный, а секреты вынесены в общий [DemoBeaconSecrets].
     */
    val beaconAuthKeyProvider: BeaconAuthKeyProvider =
        FakeBeaconAuthKeyProvider(DemoBeaconSecrets.secrets)

    /** UUID демо-меток для приложения-эмулятора (что вещать). Совпадают с белым списком гида. */
    val demoBeaconUuids: List<String> = DemoBeaconSecrets.secrets.keys.sorted()

    /**
     * Фабрика верификатора меток. Фабрика, а не синглтон: верификатор хранит анти-replay
     * историю на инстанс и принадлежит ViewModel (та зовёт reset при «забывании» эфира).
     */
    fun createBeaconVerifier(): BeaconVerifier = BeaconVerifier(beaconAuthKeyProvider)

    /**
     * Фабрика BLE-адвертайзера для приложения-эмулятора метки. Фабрика, а не синглтон:
     * принадлежит [BeaconEmulatorViewModel] (стоп вещания в onCleared). Контекст — application.
     */
    fun createBeaconAdvertiser(): BeaconAdvertiser = BeaconAdvertiser(appContext)
}
