package com.kursk1000

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "RemoteLandmarkDS"
private const val UNEXPECTED_RESPONSE = "Неожиданный ответ сервера"

/**
 * Сетевой источник карточек. Адрес бекенда приходит из BuildConfig (см. AppContainer).
 *
 * HTTP оставлен на HttpURLConnection - переезд на Retrofit имеет смысл делать
 * под прикрытием тестов, а не отдельным риском сейчас.
 */
class RemoteLandmarkDataSource(
    private val baseUrl: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun fetchLandmarks(): LandmarksResult = withContext(ioDispatcher) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/landmarks")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 5_000
                requestMethod = "GET"
            }

            val code = conn.responseCode
            if (code != 200) {
                return@withContext LandmarksResult.Error("HTTP $code")
            }

            val body = conn.inputStream.use { it.bufferedReader().readText() }
            // Если не массив (captive-portal вернул HTML со статусом 200) - локализованная ошибка
            val array = runCatching { JSONArray(body) }.getOrNull()
                ?: return@withContext LandmarksResult.Error(UNEXPECTED_RESPONSE)

            // Битый элемент пропускаем, не роняем весь ответ
            val objects = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            val landmarks = objects.map { parseLandmark(it) }
            val secrets = objects.mapNotNull { obj ->
                val uuid = obj.str("uuid").ifBlank { return@mapNotNull null }
                val secret = BeaconCode.fromHex(obj.strOrNull("beacon_secret")) ?: return@mapNotNull null
                uuid to secret
            }.toMap()

            LandmarksResult.Success(landmarks, secrets)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchLandmarks() failed", e)
            LandmarksResult.Error(e.message ?: "Неизвестная ошибка")
        } finally {
            conn?.disconnect()
        }
    }
}

// --- Разбор JSON ---
// Контент авторский, поэтому парсер устойчив к кривым данным:
// битые поля → пустые значения, битые элементы массивов пропускаются.

/** Строка с защитой от JSON-null (org.json иначе вернул бы литерал "null"). */
private fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key, "")

private fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").ifBlank { null }

private fun parseSections(arr: JSONArray?): List<Section> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val obj = arr.optJSONObject(i) ?: return@mapNotNull null
        val title = obj.str("title")
        val body = obj.str("body")
        if (title.isBlank() && body.isBlank()) null else Section(title, body)
    }
}

private fun parseFacts(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        if (arr.isNull(i)) null else arr.optString(i, "").trim().ifBlank { null }
    }
}

private fun parseGallery(arr: JSONArray?): List<MediaItem> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val obj = arr.optJSONObject(i) ?: return@mapNotNull null
        val src = obj.str("src").trim()
        if (src.isBlank()) return@mapNotNull null
        val type = when (obj.str("type").trim().lowercase()) {
            "video" -> MediaType.VIDEO
            else -> MediaType.IMAGE
        }
        MediaItem(type = type, src = src, caption = obj.str("caption"))
    }
}

private fun parseLandmark(json: JSONObject): Landmark = Landmark(
    uuid       = json.str("uuid"),
    name       = json.str("name"),
    subtitle   = json.str("subtitle"),
    year       = json.str("year"),
    summary    = json.str("summary"),
    coverImage = json.strOrNull("cover_image"),
    sections   = parseSections(json.optJSONArray("sections")),
    facts      = parseFacts(json.optJSONArray("facts")),
    gallery    = parseGallery(json.optJSONArray("gallery")),
    publicKey  = json.str("public_key"),
)
