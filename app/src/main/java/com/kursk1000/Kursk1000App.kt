package com.kursk1000

import android.app.Application

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
    }
}
