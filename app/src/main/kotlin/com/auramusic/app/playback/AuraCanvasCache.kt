/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.auramusic.innertube.YouTube
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * On-disk cache for AuraCanvas videos.
 *
 * Caching the resolved URL is only half the job: a canvas is an MP4 that the
 * player streams, so with the network gone a perfectly good cached URL still
 * renders a black rectangle. Every canvas that resolves therefore gets its
 * bytes written here, and the players read through this cache with
 * FLAG_IGNORE_CACHE_ON_ERROR so an unreachable network falls back to the copy
 * on disk instead of failing the load.
 *
 * The cache is deliberately separate from [com.auramusic.app.video.VideoPlayerSupport]:
 * the home screen can hold a dozen canvases at once, and letting those evict the
 * video stream cache would trade a stutter in the player for a black grid.
 */
@UnstableApi
object AuraCanvasCache {

    private const val CACHE_SIZE_BYTES = 192L * 1024L * 1024L
    private const val CACHE_DIR_NAME = "canvas_cache"
    private const val WRITE_BUFFER_BYTES = 64 * 1024

    @Volatile private var canvasCache: SimpleCache? = null
    @Volatile private var cacheDatabaseProvider: StandaloneDatabaseProvider? = null

    @Synchronized
    fun getOrCreateCache(context: Context): SimpleCache =
        canvasCache ?: run {
            val appContext = context.applicationContext
            val db = cacheDatabaseProvider
                ?: StandaloneDatabaseProvider(appContext).also { cacheDatabaseProvider = it }
            SimpleCache(
                File(appContext.filesDir, CACHE_DIR_NAME),
                LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
                db,
            ).also { canvasCache = it }
        }

    /**
     * Data source the canvas players read through: the disk cache in front of the same
     * OkHttp stack the rest of the app uses, so proxy settings still apply.
     */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val appContext = context.applicationContext
        val okHttpClient = OkHttpClient
            .Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .build()
        return CacheDataSource.Factory()
            .setCache(getOrCreateCache(appContext))
            .setUpstreamDataSourceFactory(
                DefaultDataSource.Factory(appContext, OkHttpDataSource.Factory(okHttpClient)),
            )
            // This is the whole point of the cache: when the network is gone the read
            // must come from disk instead of erroring out and leaving a black frame.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Downloads a canvas in full so it survives going offline.
     *
     * Playback alone is not enough: the players loop a few seconds at a time, so a
     * canvas that was only ever watched for its first few seconds would keep nothing
     * worth showing later. Failures are expected (offline, track removed) and ignored.
     */
    suspend fun prefetch(context: Context, url: String) = withContext(Dispatchers.IO) {
        runCatching {
            val dataSource = (dataSourceFactory(context) as CacheDataSource.Factory).createDataSource()
            CacheWriter(
                dataSource,
                DataSpec.Builder().setUri(url).build(),
                ByteArray(WRITE_BUFFER_BYTES),
                null,
            ).cache()
        }.onFailure { Timber.d("AuraCanvas: prefetch skipped for $url (${it.message})") }
    }
}