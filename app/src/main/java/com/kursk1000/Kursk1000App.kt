package com.kursk1000

import android.app.Application
<<<<<<< HEAD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
=======
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3

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
<<<<<<< HEAD
        CoroutineScope(Dispatchers.IO).launch { VideoCache.get(this@Kursk1000App) }
=======
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
    }
}
