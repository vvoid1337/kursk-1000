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

<<<<<<< HEAD
=======
/** Результат точечного запроса одной карточки (`GET /landmark/{uuid}`). */
sealed interface LandmarkResult {
    data class Success(val landmark: Landmark) : LandmarkResult
    data object NotFound : LandmarkResult
    data class Error(val message: String) : LandmarkResult
}

>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
/** Результат запроса всего списка (`GET /landmarks`). Внутренний словарь data-слоя. */
sealed interface LandmarksResult {
    data class Success(val landmarks: List<Landmark>) : LandmarksResult
    data class Error(val message: String) : LandmarksResult
}

/**
 * Единственный шов между UI-слоем (ViewModel) и тем, откуда берутся данные.
 *
<<<<<<< HEAD
 * `landmarks` — поток состояния списка. Его наполняет offline-first реализация:
 * `landmarks` маппится из DAO-Flow, а `refresh()` делает сеть → upsert.
 *
 * `refresh()` перезагружает список (init ViewModel и кнопка «Повторить»).
=======
 * `landmarks` — поток состояния списка. Сейчас его наполняет сеть (см.
 * [NetworkLandmarkRepository]); когда появится Room-кэш (следующая задача), за этим
 * же интерфейсом встанет offline-first реализация: `landmarks` будет маппиться из
 * DAO-Flow, а `refresh()` — делать сеть → upsert. Поверхность ViewModel при этом не
 * меняется — именно ради этого репозиторий введён заранее.
 *
 * `refresh()` перезагружает список (init ViewModel и кнопка «Повторить»).
 * `getLandmark()` — подстраховочный точечный запрос на случай промаха кэша.
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
 */
interface LandmarkRepository {
    val landmarks: Flow<LandmarkLoad>
    suspend fun refresh()
<<<<<<< HEAD
=======
    suspend fun getLandmark(uuid: String): LandmarkResult
>>>>>>> d3d467005839c8b7d75b98510e760e4604d0bba3
}
