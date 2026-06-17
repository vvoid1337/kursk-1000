package com.kursk1000

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

suspend fun fetchLandmark(uuid: String): LandmarkResult = withContext(Dispatchers.IO) {
    try {
        val url = URL("$BASE_URL/landmark/$uuid")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.requestMethod = "GET"

        val code = conn.responseCode
        if (code == 404) return@withContext LandmarkResult.NotFound

        if (code != 200) {
            return@withContext LandmarkResult.Error("HTTP $code")
        }

        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)

        LandmarkResult.Success(
            Landmark(
                uuid        = json.getString("uuid"),
                name        = json.getString("name"),
                emoji       = json.getString("emoji"),
                description = json.getString("description"),
                fact        = json.getString("fact"),
                year        = json.getString("year"),
            )
        )
    } catch (e: Exception) {
        Log.e(TAG, "fetchLandmark($uuid) failed", e)
        LandmarkResult.Error(e.message ?: "Неизвестная ошибка")
    }
}
