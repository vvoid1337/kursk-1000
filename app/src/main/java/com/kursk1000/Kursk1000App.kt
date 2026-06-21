package com.kursk1000

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application-класс: владеет [AppContainer] на всё время жизни процесса.
 * Зарегистрирован в манифесте через android:name=".Kursk1000App".
 *
 * Из него же фабрика ViewModel достаёт контейнер (см. LandmarkViewModel.Factory).
 */
class Kursk1000App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Прогрев дискового кэша видео — оптимизация. Её сбой (например, занятая блокировка
        // папки кэша) не должен ронять старт приложения, поэтому ловим исключение здесь.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { VideoCache.get(this@Kursk1000App) }
                .onFailure { Log.w("Kursk1000App", "video cache warm-up failed", it) }
        }
    }
}
