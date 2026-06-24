package com.kursk1000

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

class OfflineFirstLandmarkRepository(
    private val dao: LandmarkDao,
    private val remote: RemoteLandmarkDataSource,
    // Секреты в Keystore, а не в Room - сырьё ключа не должно лежать в кэше
    private val provisioner: BeaconSecretProvisioner,
) : LandmarkRepository {

    private sealed interface RefreshState {
        data object Idle : RefreshState
        data object Loaded : RefreshState
        data class Error(val message: String) : RefreshState
    }

    private val refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)

    override val landmarks: Flow<LandmarkLoad> =
        combine(dao.observeAll(), refreshState) { entities, refresh ->
            val byUuid = entities.associate { it.uuid.uppercase() to it.toDomain() }
            val error = (refresh as? RefreshState.Error)?.message
            when {
                // Кэш есть — показываем, рядом несём ошибку обновления если была
                byUuid.isNotEmpty() -> LandmarkLoad.Ready(byUuid, refreshError = error)
                // Кэш пуст и обновление упало — ошибка с кнопкой «Повторить»
                error != null -> LandmarkLoad.Failed(error)
                // Загрузили, но список пуст — не крутим спиннер вечно
                refresh is RefreshState.Loaded -> LandmarkLoad.Ready(byUuid)
                else -> LandmarkLoad.Loading
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun refresh() {
        when (val result = remote.fetchLandmarks()) {
            is LandmarksResult.Success -> try {
                // Секреты идут в Keystore идемпотентно — проверка работает офлайн после перезапуска.
                if (result.secrets.isNotEmpty()) provisioner.provision(result.secrets)
                // Пустой ответ (регрессия деплоя?) не стирает рабочий кэш
                if (result.landmarks.isNotEmpty()) {
                    dao.replaceAll(result.landmarks.map { it.toEntity() })
                }
                refreshState.value = RefreshState.Loaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сбой записи в БД (нет места и т.п.) - деградируем до ошибки, не роняем приложение
                refreshState.value = RefreshState.Error(e.message ?: "Ошибка сохранения данных")
            }

            is LandmarksResult.Error -> refreshState.value = RefreshState.Error(result.message)
        }
    }
}
