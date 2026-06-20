package com.kursk1000

import kotlinx.coroutines.flow.Flow

/**
 * Состояние загрузки полного списка карточек (источник — `GET /landmarks`).
 *
 * Раньше жил внутри LandmarkViewModel. Поднят в data-слой, потому что теперь его
 * формирует репозиторий, а ViewModel лишь отдаёт наружу.
 */
sealed interface LandmarkLoad {
    data object Loading : LandmarkLoad
    data class Ready(val byUuid: Map<String, Landmark>) : LandmarkLoad
    data class Failed(val message: String) : LandmarkLoad
}

/** Результат запроса всего списка (`GET /landmarks`). Внутренний словарь data-слоя. */
sealed interface LandmarksResult {
    data class Success(val landmarks: List<Landmark>) : LandmarksResult
    data class Error(val message: String) : LandmarksResult
}

/**
 * Единственный шов между UI-слоем (ViewModel) и тем, откуда берутся данные.
 *
 * `landmarks` — поток состояния списка. Его наполняет offline-first реализация:
 * `landmarks` маппится из DAO-Flow, а `refresh()` делает сеть → upsert.
 *
 * `refresh()` перезагружает список (init ViewModel и кнопка «Повторить»).
 */
interface LandmarkRepository {
    val landmarks: Flow<LandmarkLoad>
    suspend fun refresh()
}
