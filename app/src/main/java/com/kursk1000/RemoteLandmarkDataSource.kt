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

/**
 * Сетевой источник карточек. Раньше это были свободные top-level suspend-функции в
 * ApiClient.kt с захардкоженным BASE_URL — теперь класс с инъекцией адреса и
 * диспетчера. Благодаря этому его можно подменять/тестировать, а адрес бекенда
 * приходит из BuildConfig (см. AppContainer), а не из константы в исходнике.
 *
 * HTTP-движок намеренно оставлен на `HttpURLConnection`: парсер ниже устойчив к кривым
 * данным и проверен в бою. Переезд на Retrofit + kotlinx.serialization имеет смысл делать
 * заодно с Room, под прикрытием тестов, а не отдельным риском сейчас.
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

            // Битый элемент (не-объект) пропускаем, а не роняем весь ответ — как и парсеры
            // секций/фактов/галереи ниже. Один плохой элемент не должен стоить всего списка.
            val landmarks = (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { parseLandmark(it) }
            }

            LandmarksResult.Success(landmarks)
        } catch (e: CancellationException) {
            // Отмена корутины (например, пересоздание ViewModel на лету) — не ошибка загрузки:
            // пробрасываем, иначе структурированная отмена ломается, а в UI летит ложная ошибка.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchLandmarks() failed", e)
            LandmarksResult.Error(e.message ?: "Неизвестная ошибка")
        } finally {
            conn?.disconnect()
        }
    }
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
