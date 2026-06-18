package com.kursk1000

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ApiClient"
private const val BASE_URL = "http://192.168.0.163:8000"

/** Секция вики-карточки: заголовок + абзац текста (История, Архитектура, …). */
data class Section(
    val title: String,
    val body: String,
)

/** Тип медиа в галерее. Неизвестные значения трактуем как изображение. */
enum class MediaType { IMAGE, VIDEO }

/** Элемент галереи: фото или видео с подписью. `src` — уже абсолютный URL (CDN собирает бекенд). */
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

sealed class LandmarkResult {
    data class Success(val landmark: Landmark) : LandmarkResult()
    data object NotFound : LandmarkResult()
    data class Error(val message: String) : LandmarkResult()
}

sealed class LandmarksResult {
    data class Success(val landmarks: List<Landmark>) : LandmarksResult()
    data class Error(val message: String) : LandmarksResult()
}

// --- Разбор JSON ----------------------------------------------------------
// Контент авторский (заполняется вручную), поэтому парсер устойчив к кривым
// данным: отсутствующие поля → пустые значения, битые элементы массивов
// пропускаются. Один плохой элемент не должен ронять всю карточку.

/** Строка с защитой от JSON-null: org.json иначе вернул бы литерал "null". */
private fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key, "")

/** Необязательная строка: пусто/`null` → `null` (для cover_image и подобных). */
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
        if (src.isBlank()) return@mapNotNull null  // без рабочей ссылки элемент бесполезен
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
    emoji      = json.str("emoji"),
    subtitle   = json.str("subtitle"),
    year       = json.str("year"),
    summary    = json.str("summary"),
    coverImage = json.strOrNull("cover_image"),
    sections   = parseSections(json.optJSONArray("sections")),
    facts      = parseFacts(json.optJSONArray("facts")),
    gallery    = parseGallery(json.optJSONArray("gallery")),
    publicKey  = json.str("public_key"),
)

// --- Сетевые вызовы -------------------------------------------------------

suspend fun fetchLandmark(uuid: String): LandmarkResult = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val url = URL("$BASE_URL/landmark/$uuid")
        conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 3_000
            requestMethod = "GET"
        }

        val code = conn.responseCode
        if (code == 404) return@withContext LandmarkResult.NotFound

        if (code != 200) {
            return@withContext LandmarkResult.Error("HTTP $code")
        }

        val body = conn.inputStream.use { it.bufferedReader().readText() }
        val json = JSONObject(body)

        LandmarkResult.Success(parseLandmark(json))
    } catch (e: Exception) {
        Log.e(TAG, "fetchLandmark($uuid) failed", e)
        LandmarkResult.Error(e.message ?: "Неизвестная ошибка")
    } finally {
        conn?.disconnect()
    }
}

suspend fun fetchLandmarks(): LandmarksResult = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val url = URL("$BASE_URL/landmarks")
        conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            // Список теперь несёт полный контент всех карточек — даём чуть больше времени на чтение.
            readTimeout = 5_000
            requestMethod = "GET"
        }

        val code = conn.responseCode
        if (code != 200) {
            return@withContext LandmarksResult.Error("HTTP $code")
        }

        val body = conn.inputStream.use { it.bufferedReader().readText() }
        val array = JSONArray(body)

        val landmarks = (0 until array.length()).map { i ->
            parseLandmark(array.getJSONObject(i))
        }

        LandmarksResult.Success(landmarks)
    } catch (e: Exception) {
        Log.e(TAG, "fetchLandmarks() failed", e)
        LandmarksResult.Error(e.message ?: "Неизвестная ошибка")
    } finally {
        conn?.disconnect()
    }
}