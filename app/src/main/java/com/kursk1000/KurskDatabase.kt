package com.kursk1000

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

// Вся Room-обвязка одним файлом: база, таблица, DAO и TypeConverter'ы всегда меняются
// вместе и по отдельности не существуют. Доменная модель (Landmark) живёт отдельно —
// слой данных зависит от неё, а не наоборот.

@Database(entities = [LandmarkEntity::class], version = 1, exportSchema = false)
@TypeConverters(LandmarkConverters::class)
abstract class KurskDatabase : RoomDatabase() {
    abstract fun landmarkDao(): LandmarkDao
}

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

@Dao
abstract class LandmarkDao {
    @Query("SELECT * FROM landmarks")
    abstract fun observeAll(): Flow<List<LandmarkEntity>>

    @Transaction
    open suspend fun replaceAll(items: List<LandmarkEntity>) {
        clear()
        upsert(items)
    }

    @Upsert
    abstract suspend fun upsert(items: List<LandmarkEntity>)

    @Query("DELETE FROM landmarks")
    abstract suspend fun clear()
}

class LandmarkConverters {
    @TypeConverter
    fun sectionsToJson(value: List<Section>): String {
        val array = JSONArray()
        value.forEach { section ->
            array.put(
                JSONObject()
                    .put("title", section.title)
                    .put("body", section.body)
            )
        }
        return array.toString()
    }

    @TypeConverter
    fun jsonToSections(value: String): List<Section> =
        runCatching {
            val array = JSONArray(value)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                Section(
                    title = obj.safeString("title"),
                    body = obj.safeString("body"),
                )
            }
        }.getOrDefault(emptyList())

    @TypeConverter
    fun factsToJson(value: List<String>): String {
        val array = JSONArray()
        value.forEach { array.put(it) }
        return array.toString()
    }

    @TypeConverter
    fun jsonToFacts(value: String): List<String> =
        runCatching {
            val array = JSONArray(value)
            (0 until array.length()).mapNotNull { index ->
                if (array.isNull(index)) null else array.optString(index, "").trim().ifBlank { null }
            }
        }.getOrDefault(emptyList())

    @TypeConverter
    fun galleryToJson(value: List<MediaItem>): String {
        val array = JSONArray()
        value.forEach { item ->
            array.put(
                JSONObject()
                    .put("type", item.type.name.lowercase())
                    .put("src", item.src)
                    .put("caption", item.caption)
            )
        }
        return array.toString()
    }

    @TypeConverter
    fun jsonToGallery(value: String): List<MediaItem> =
        runCatching {
            val array = JSONArray(value)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val src = obj.safeString("src").trim()
                if (src.isBlank()) return@mapNotNull null
                val type = when (obj.safeString("type").trim().lowercase()) {
                    "video" -> MediaType.VIDEO
                    else -> MediaType.IMAGE
                }
                MediaItem(
                    type = type,
                    src = src,
                    caption = obj.safeString("caption"),
                )
            }
        }.getOrDefault(emptyList())
}

private fun JSONObject.safeString(key: String): String =
    if (isNull(key)) "" else optString(key, "")
