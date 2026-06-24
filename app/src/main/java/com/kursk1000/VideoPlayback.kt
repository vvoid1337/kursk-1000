package com.kursk1000

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/**
 * Дисковый кэш видео для ExoPlayer. Должен быть синглтоном - два экземпляра на одну папку
 * падают с ошибкой блокировки. Лежит в cacheDir: система вычистит при нехватке места.
 */
@OptIn(UnstableApi::class)
object VideoCache {
    private const val MAX_BYTES = 256L * 1024 * 1024 // 256 МБ, LRU

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache =
        instance ?: synchronized(this) {
            instance ?: SimpleCache(
                File(context.applicationContext.cacheDir, "video-cache"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { instance = it }
        }
}

/**
 * ExoPlayer с кэшированием, управлением аудиофокусом и паузой при отключении наушников.
 * Вызывающий обязан вызвать release() - см. FullscreenVideoPlayer в LandmarkCard.
 */
@OptIn(UnstableApi::class)
fun buildCachingPlayer(context: Context): ExoPlayer {
    val upstream = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
    val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(VideoCache.get(context))
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR) // проблемы с кэшем - читаем из сети
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .build()
}
