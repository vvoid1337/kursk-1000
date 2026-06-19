package com.kursk1000

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

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
