package com.kursk1000

import androidx.compose.runtime.Immutable

// Доменная модель достопримечательности — только для чтения

@Immutable
data class Section(
    val title: String,
    val body: String,
)

// Неизвестный тип трактуем как изображение
enum class MediaType { IMAGE, VIDEO }

@Immutable
data class MediaItem(
    val type: MediaType,
    val src: String,
    val caption: String,
)

@Immutable
data class Landmark(
    val uuid: String,
    val name: String,
    val subtitle: String,
    val year: String,
    val summary: String,
    val coverImage: String?,
    val sections: List<Section>,
    val facts: List<String>,
    val gallery: List<MediaItem>,
    val publicKey: String,
)
