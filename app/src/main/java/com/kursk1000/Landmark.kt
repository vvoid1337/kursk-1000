package com.kursk1000

import androidx.compose.runtime.Immutable

// Доменная модель достопримечательности. Раньше жила в ApiClient.kt вперемешку с сетью;
// вынесена отдельно, чтобы UI и data-слой зависели от модели, а не от деталей загрузки.
//
// @Immutable — обещание Compose-компилятору, что значение глубоко неизменяемо (списки
// внутри не мутируются после создания). Без него List-поля делают тип «нестабильным» и
// карточка перерисовывается чаще нужного. Здесь обещание честное: модель собирается
// парсером один раз и дальше только читается.

/** Секция вики-карточки: заголовок + абзац текста (История, Архитектура, …). */
@Immutable
data class Section(
    val title: String,
    val body: String,
)

/** Тип медиа в галерее. Неизвестные значения трактуем как изображение. */
enum class MediaType { IMAGE, VIDEO }

/** Элемент галереи: фото или видео с подписью. `src` — уже абсолютный URL (CDN собирает бекенд). */
@Immutable
data class MediaItem(
    val type: MediaType,
    val src: String,
    val caption: String,
)

/**
 * Достопримечательность — целиком соответствует ответу `GET /landmarks` нового API.
 *
 * Карточка кэшируется клиентом целиком, поэтому модель плоская и сериализуемая.
 * Любое поле может прийти пустым (бекенд отдаёт дефолты), UI обязан это переживать.
 */
@Immutable
data class Landmark(
    val uuid: String,
    val name: String,
    val emoji: String,
    val subtitle: String,
    val year: String,
    val summary: String,
    val coverImage: String?,
    val sections: List<Section>,
    val facts: List<String>,
    val gallery: List<MediaItem>,
    val publicKey: String,
)
