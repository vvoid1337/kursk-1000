package com.kursk1000

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

class OfflineFirstLandmarkRepository(
    private val dao: LandmarkDao,
    private val remote: RemoteLandmarkDataSource,
) : LandmarkRepository {

    private val refreshError = MutableStateFlow<String?>(null)

    override val landmarks: Flow<LandmarkLoad> =
        combine(dao.observeAll(), refreshError) { entities, error ->
            when {
                entities.isNotEmpty() ->
                    LandmarkLoad.Ready(entities.associate { it.uuid.uppercase() to it.toDomain() })
                error != null -> LandmarkLoad.Failed(error)
                else -> LandmarkLoad.Loading
            }
        }

    override suspend fun refresh() {
        when (val result = remote.fetchLandmarks()) {
            is LandmarksResult.Success -> {
                dao.replaceAll(result.landmarks.map { it.toEntity() })
                refreshError.value = null
            }
            is LandmarksResult.Error -> refreshError.value = result.message
        }
    }
}
