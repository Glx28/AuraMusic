/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.auramusic.app.constants.CanvasUrlCacheKey
import com.auramusic.app.utils.dataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Single source of truth for AuraCanvas video lookups.
 *
 * Resolution chain for a (title, artist, album) query:
 *   1) Remote AuraMusicCanvasServer (https://canvas.auramusic.site)
 *      which does the Spotify trackId search server-side and returns the
 *      canvas URL from the internal `canvaz-cache` endpoint.
 *
 * The result is cached in memory (24h positive, 1h negative) so a repeat
 * track only hits the network once, and in DataStore so a track already
 * resolved once stays resolved across restarts - otherwise every launch
 * re-asked the server for the whole library, which is the visible stall
 * while the home screen's canvas images pop in one at a time.
 *
 * No Spotify developer key, sp_dc, or protobuf parsing is needed in the app –
 * the server owns all of that.
 */
object AuraCanvasRepository {

    private const val REMOTE_BASE_URL = "https://canvas.auramusic.site"

    private const val POSITIVE_TTL_MS = 24 * 60 * 60 * 1000L    // 24h
    private const val NEGATIVE_TTL_MS = 60 * 60 * 1000L         // 1h

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            // First call after idle can take 30–90s if the server cold-starts.
            requestTimeoutMillis = 90_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 90_000
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    /** Resolved URL cache keyed by normalized(title|artist|album). */
    private data class CacheEntry(val url: String?, val expiresAt: Long)
    private val resultCache = mutableMapOf<String, CacheEntry>()

    /**
     * The persisted mirror of [resultCache]. Loaded once per process from DataStore and
     * written back after every remote answer, so the second launch of a session paints
     * canvases without touching the network.
     */
    @Volatile private var appContext: Context? = null
    @Volatile private var persistedLoaded = false
    private val persistMutex = Mutex()

    @Volatile private var warmedUp = false

    /**
     * Hands the repository an application context so it can read and write its cache.
     * Safe to call repeatedly; the first context wins.
     */
    fun attachContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * Fire-and-forget ping of the `/health` endpoint to wake the server.
     * Call once on app start or when the player UI becomes visible.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        GlobalScope.launch(Dispatchers.IO + SupervisorJob()) {
            runCatching {
                client.get("$REMOTE_BASE_URL/health")
                Timber.d("AuraCanvas: warm-up ping sent")
            }
        }
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\bfeat\\.?|\\bft\\.?|\\bwith\\b"), " ")
            .replace(Regex("\\b(remaster(ed)?|live|explicit|official video|lyrics|audio|hd|hq)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    // --------------------------------------------------------------
    //  Remote layer (canvas server)
    // --------------------------------------------------------------

    private suspend fun remoteLookup(
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long?,
    ): String? = withContext(Dispatchers.IO) {
        if (title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()) return@withContext null
        try {
            val response: HttpResponse = client.get("$REMOTE_BASE_URL/api/canvas") {
                url {
                    if (!title.isNullOrBlank()) parameters.append("song", title)
                    if (!artist.isNullOrBlank()) parameters.append("artist", artist)
                    if (!album.isNullOrBlank()) parameters.append("album", album)
                    if (durationMs != null && durationMs > 0) parameters.append("durationMs", durationMs.toString())
                }
            }
            if (response.status == HttpStatusCode.NotFound) return@withContext null
            if (!response.status.isSuccess()) {
                Timber.w("AuraCanvas: remote HTTP ${response.status.value} for $title / $artist")
                return@withContext null
            }
            val body = response.bodyAsText()
            extractCanvasUrl(body)
        } catch (t: Throwable) {
            Timber.w(t, "AuraCanvas: remote lookup failed for $title / $artist")
            null
        }
    }

    private fun extractCanvasUrl(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["canvasUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["canvasesList"]?.jsonArray?.firstOrNull()?.jsonObject?.get("canvasUrl")
                    ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (obj["data"] as? JsonObject)
                    ?.get("canvasesList")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("canvasUrl")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // --------------------------------------------------------------
    //  Persisted cache (DataStore)
    // --------------------------------------------------------------

    private const val MAX_PERSISTED_ENTRIES = 400

    private fun serializeCache(entries: Map<String, CacheEntry>): String {
        val array = org.json.JSONArray()
        val now = System.currentTimeMillis()
        entries.forEach { (key, entry) ->
            if (entry.expiresAt <= now) return@forEach
            array.put(
                org.json.JSONObject().apply {
                    put("k", key)
                    put("u", entry.url ?: "")
                    put("e", entry.expiresAt)
                },
            )
        }
        return array.toString()
    }

    private fun parseCache(raw: String?): Map<String, CacheEntry> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = mutableMapOf<String, CacheEntry>()
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val key = obj.optString("k")
                if (key.isEmpty()) continue
                out[key] = CacheEntry(
                    url = obj.optString("u").takeIf { it.isNotEmpty() },
                    expiresAt = obj.optLong("e"),
                )
            }
        }
        return out
    }

    /**
     * Pulls the persisted cache into memory once per process. Entries already expired on
     * disk are dropped here, so a long-dead cache cannot keep pushing writes at launch.
     */
    private suspend fun ensurePersistedLoaded() {
        if (persistedLoaded) return
        val ctx = appContext ?: return
        persistMutex.withLock {
            if (persistedLoaded) return
            // Read through the reactive API, not the blocking helper: this runs while a
            // composable is waiting for the canvas URL.
            val raw = runCatching {
                ctx.dataStore.data.first()[CanvasUrlCacheKey]
            }.getOrNull()
            val now = System.currentTimeMillis()
            val loaded = parseCache(raw).filterValues { it.expiresAt > now }
            synchronized(resultCache) {
                // Memory wins on conflict: this process may already know fresher answers.
                resultCache.putAll(loaded.filterKeys { it !in resultCache })
            }
            persistedLoaded = true
        }
    }

    private suspend fun persistCache() {
        val ctx = appContext ?: return
        val snapshot = synchronized(resultCache) { resultCache.toMap() }
        val trimmed = snapshot.entries
            .sortedByDescending { it.value.expiresAt }
            .take(MAX_PERSISTED_ENTRIES)
            .associate { it.key to it.value }
        runCatching {
            withContext(Dispatchers.IO) {
                ctx.dataStore.edit { it[CanvasUrlCacheKey] = serializeCache(trimmed) }
            }
        }.onFailure { Timber.w(it, "AuraCanvas: could not persist cache") }
    }

    // --------------------------------------------------------------
    //  Public API
    // --------------------------------------------------------------

    /**
     * Resolve a canvas URL for the current track. Returns null if the server
     * has no canvas for it. Safe to call on every track change — results are
     * memoised.
     */
    suspend fun findCanvasUrl(
        title: String?,
        artist: String?,
        album: String? = null,
        durationMs: Long? = null,
    ): String? {
        if (title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()) return null
        val key = listOf(title, artist, album, durationMs?.toString()).joinToString("\u0001") { normalize(it ?: "") }
        var now = System.currentTimeMillis()

        // Memory, then disk: both use the same expiry, so a hit here is indistinguishable
        // from a live lookup apart from the missing round-trip.
        synchronized(resultCache) {
            val hit = resultCache[key]
            if (hit != null && hit.expiresAt > now) return hit.url
        }
        ensurePersistedLoaded()
        now = System.currentTimeMillis()
        synchronized(resultCache) {
            val hit = resultCache[key]
            if (hit != null && hit.expiresAt > now) return hit.url
        }

        warmUp()
        val remoteHit = remoteLookup(title, artist, album, durationMs)
        now = System.currentTimeMillis()
        synchronized(resultCache) {
            resultCache[key] = CacheEntry(
                remoteHit,
                now + if (remoteHit != null) POSITIVE_TTL_MS else NEGATIVE_TTL_MS,
            )
        }
        // Remember it for the next launch. Failures are invisible on purpose: a lookup that
        // already produced its answer must not turn into an error because of the cache.
        if (appContext != null) persistCache()
        return remoteHit
    }
}
