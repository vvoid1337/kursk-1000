package com.kursk1000

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application-класс. Зарегистрирован в манифесте: android:name=".Kursk1000App".
 * Отсюда ViewModel-фабрика достаёт [AppContainer].
 */
class Kursk1000App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Прогрев кэша видео. Сбой не должен ронять старт - ловим здесь.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { VideoCache.get(this@Kursk1000App) }
                .onFailure { Log.w("Kursk1000App", "video cache warm-up failed", it) }
        }
    }
}
