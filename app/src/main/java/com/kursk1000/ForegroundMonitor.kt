package com.kursk1000

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Флаг "приложение на переднем плане" по жизненному циклу процесса, а не Activity -
 * поворот экрана не дёргает скан. Вынесен в интерфейс, чтобы в тестах подменять фейком.
 */
interface ForegroundMonitor {
    val appInForeground: StateFlow<Boolean>
}

/**
 * Боевая реализация. Живёт в [AppContainer] на весь процесс - снимать наблюдателя не нужно.
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
