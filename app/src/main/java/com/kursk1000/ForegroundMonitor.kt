package com.kursk1000

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Признак «приложение на переднем плане» по жизненному циклу всего процесса
 * (ProcessLifecycleOwner), а не Activity: поворот экрана (stop→start Activity) не дёргает скан.
 *
 * Вынесен из [LandmarkViewModel] в инъектируемый шов. Раньше ViewModel напрямую звала
 * статический ProcessLifecycleOwner.get(), который не инициализируется в обычном JVM-тесте,
 * из-за чего логику гейтинга скана нельзя было проверить без Android. Теперь это [StateFlow],
 * который в тестах подменяется фейком.
 */
interface ForegroundMonitor {
    val appInForeground: StateFlow<Boolean>
}

/**
 * Боевая реализация. Живёт в [AppContainer] на весь процесс, поэтому наблюдателя снимать не
 * нужно — он не переживает процесс (в отличие от ViewModel, которая раньше была обязана
 * отписываться в onCleared, чтобы синглтон-овнер её не удерживал).
 */
class ProcessForegroundMonitor : ForegroundMonitor, DefaultLifecycleObserver {

    private val _appInForeground = MutableStateFlow(false)
    override val appInForeground: StateFlow<Boolean> = _appInForeground.asStateFlow()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { _appInForeground.value = true }
    override fun onStop(owner: LifecycleOwner) { _appInForeground.value = false }
}
