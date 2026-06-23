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
    // Секреты меток уходят сюда (Keystore), а не в Room: сырьё ключа не должно лежать в кэше.
    private val provisioner: BeaconSecretProvisioner,
) : LandmarkRepository {

    // Состояние последней сетевой синхронизации, отдельно от содержимого кэша. Нужно, чтобы
    // отличать «ещё не грузили» от «загрузили пустой список» и доносить ошибку обновления
    // до UI даже когда кэш не пуст (иначе сбой refresh при наличии кэша был бы не виден).
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
                // Есть кэш — показываем его. Ошибку обновления (если была) проносим рядом,
                // чтобы UI мог предупредить о неактуальности, не пряча контент.
                byUuid.isNotEmpty() -> LandmarkLoad.Ready(byUuid, refreshError = error)
                // Кэш пуст и обновиться не удалось — полноценная ошибка с кнопкой «Повторить».
                error != null -> LandmarkLoad.Failed(error)
                // Кэш пуст, но загрузка успешно завершилась — «загружено, но пусто»,
                // а не вечный спиннер.
                refresh is RefreshState.Loaded -> LandmarkLoad.Ready(byUuid)
                else -> LandmarkLoad.Loading
            }
        }.flowOn(Dispatchers.Default) // associate/uppercase/toDomain — не на главном потоке

    override suspend fun refresh() {
        when (val result = remote.fetchLandmarks()) {
            is LandmarksResult.Success -> try {
                // Секреты — в Keystore (идемпотентно, переживает перезапуск → проверка работает
                // офлайн). Пустую карту не провижиним, чтобы не трогать уже импортированные ключи.
                if (result.secrets.isNotEmpty()) provisioner.provision(result.secrets)
                // Пустой успешный ответ (типичная регрессия деплоя бекенда) НЕ должен стирать
                // рабочий кэш: пропускаем запись, но синхронизацию считаем завершённой.
                if (result.landmarks.isNotEmpty()) {
                    dao.replaceAll(result.landmarks.map { it.toEntity() })
                }
                refreshState.value = RefreshState.Loaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сбой записи в БД (нет места на диске и т.п.) должен деградировать до ошибки,
                // а не ронять приложение из viewModelScope.launch без обработчика.
                refreshState.value = RefreshState.Error(e.message ?: "Ошибка сохранения данных")
            }

            is LandmarksResult.Error -> refreshState.value = RefreshState.Error(result.message)
        }
    }
}
