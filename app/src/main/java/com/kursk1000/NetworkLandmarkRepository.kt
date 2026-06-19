package com.kursk1000

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сетевая (in-memory) реализация [LandmarkRepository]: держит последний загруженный
 * список в [MutableStateFlow] и перезаливает его из сети по `refresh()`.
 *
 * Это «нулевая» реализация offline-first шва. Когда добавим Room, на её место встанет
 * `OfflineFirstLandmarkRepository` (читает DAO-Flow, пишет туда же при refresh), а
 * остальной код — ViewModel и UI — не изменится.
 *
 * Ключи словаря `byUuid` нормализованы в верхний регистр: сканер видит UUID в верхнем
 * регистре, и резолв карточки по нему должен совпадать без учёта регистра бекенда.
 */
class NetworkLandmarkRepository(
    private val remote: RemoteLandmarkDataSource,
) : LandmarkRepository {

    private val _landmarks = MutableStateFlow<LandmarkLoad>(LandmarkLoad.Loading)
    override val landmarks: Flow<LandmarkLoad> = _landmarks.asStateFlow()

    override suspend fun refresh() {
        _landmarks.value = LandmarkLoad.Loading
        _landmarks.value = when (val result = remote.fetchLandmarks()) {
            is LandmarksResult.Success ->
                LandmarkLoad.Ready(result.landmarks.associateBy { it.uuid.uppercase() })
            is LandmarksResult.Error -> LandmarkLoad.Failed(result.message)
        }
    }

    override suspend fun getLandmark(uuid: String): LandmarkResult = remote.fetchLandmark(uuid)
}
