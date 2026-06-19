package com.kursk1000

import android.content.Context
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

    /** Репозиторий-синглтон: список карточек грузится один раз и переживает Activity. */
    val landmarkRepository: LandmarkRepository = NetworkLandmarkRepository(remoteDataSource)

    /**
     * Фабрика BLE-сканера. Намеренно фабрика, а не синглтон: сканер принадлежит
     * ViewModel (создаётся в ней, освобождается в onCleared), поэтому на каждое
     * создание ViewModel выдаём свежий экземпляр. Контекст — application, без утечки.
     */
    fun createBleScanner(): BleScanner = RealBleScanner(appContext)
}
