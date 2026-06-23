package com.kursk1000

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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

    // источник секрета
    val beaconAuthKeyProvider: KeystoreBeaconAuthKeyProvider = KeystoreBeaconAuthKeyProvider()

    /** Репозиторий-синглтон: список карточек кешируется и переживает перезапуск приложения. */
    val landmarkRepository: LandmarkRepository =
        OfflineFirstLandmarkRepository(database.landmarkDao(), remoteDataSource, beaconAuthKeyProvider)

    // монитор переднего плана для viewmodel
    val appForegroundMonitor: ForegroundMonitor = ProcessForegroundMonitor()

    /**
     * Фабрика BLE-сканера. Намеренно фабрика, а не синглтон: сканер принадлежит
     * ViewModel (создаётся в ней, освобождается в onCleared), поэтому на каждое
     * создание ViewModel выдаём свежий экземпляр. Контекст — application, без утечки.
     */
    fun createBleScanner(): BleScanner = RealBleScanner(appContext)

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
