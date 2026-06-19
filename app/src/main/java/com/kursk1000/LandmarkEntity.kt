package com.kursk1000

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "landmarks")
data class LandmarkEntity(
    @PrimaryKey val uuid: String,
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

fun LandmarkEntity.toDomain(): Landmark =
    Landmark(uuid, name, emoji, subtitle, year, summary, coverImage, sections, facts, gallery, publicKey)

fun Landmark.toEntity(): LandmarkEntity =
    LandmarkEntity(uuid, name, emoji, subtitle, year, summary, coverImage, sections, facts, gallery, publicKey)
