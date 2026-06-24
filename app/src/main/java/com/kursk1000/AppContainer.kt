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
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    /** источник секрета*/
    val beaconAuthKeyProvider: KeystoreBeaconAuthKeyProvider = KeystoreBeaconAuthKeyProvider()

    /** список карточек кешируется и переживает перезапуск приложения*/
    val landmarkRepository: LandmarkRepository =
        OfflineFirstLandmarkRepository(database.landmarkDao(), remoteDataSource, beaconAuthKeyProvider)

    /** монитор переднего плана для viewmodel*/
    val appForegroundMonitor: ForegroundMonitor = ProcessForegroundMonitor()

    /** фабрика BLE сканера*/
    fun createBleScanner(): BleScanner = RealBleScanner(appContext)

    /** фабрика верификатора меток*/
    fun createBeaconVerifier(): BeaconVerifier = BeaconVerifier(beaconAuthKeyProvider)

    /** фабрика для эмулятора маяка*/
    fun createBeaconAdvertiser(): BeaconAdvertiser = BeaconAdvertiser(appContext)
}
