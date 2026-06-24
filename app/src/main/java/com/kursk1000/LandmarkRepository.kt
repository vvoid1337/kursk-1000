package com.kursk1000

import kotlinx.coroutines.flow.Flow

/**
 * Состояние загрузки списка карточек.
 */
sealed interface LandmarkLoad {
    data object Loading : LandmarkLoad

    /**
     * Данные есть. [byUuid] может быть пустым - это «загружено, но пусто».
     * [refreshError] != null - кэш есть, но последнее обновление упало; UI показывает баннер.
     */
    data class Ready(
        val byUuid: Map<String, Landmark>,
        val refreshError: String? = null,
    ) : LandmarkLoad

    data class Failed(val message: String) : LandmarkLoad
}

/** Результат запроса списка с сервера. */
sealed interface LandmarksResult {
    /**
     * [secrets] (uuid → HMAC-секрет) идут в Keystore, а не в Room - сырьё ключа не должно лежать в кэше.
     */
    data class Success(
        val landmarks: List<Landmark>,
        val secrets: Map<String, ByteArray> = emptyMap(),
    ) : LandmarksResult

    data class Error(val message: String) : LandmarksResult
}

/**
 * Шов между ViewModel и источником данных.
 * [landmarks] - горячий поток состояния списка (offline-first через Room).
 * [refresh] - перезагрузка (init ViewModel и кнопка «Повторить»).
 */
interface LandmarkRepository {
    val landmarks: Flow<LandmarkLoad>
    suspend fun refresh()
}
