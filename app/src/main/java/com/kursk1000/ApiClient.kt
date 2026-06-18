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

data class Landmark(
    val uuid: String,
    val name: String,
    val emoji: String,
    val description: String,
    val fact: String,
    val year: String,
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

private fun parseLandmark(json: JSONObject): Landmark = Landmark(
    uuid        = json.getString("uuid"),
    name        = json.getString("name"),
    emoji       = json.getString("emoji"),
    description = json.getString("description"),
    fact        = json.getString("fact"),
    year        = json.getString("year"),
)

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
            readTimeout = 3_000
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
