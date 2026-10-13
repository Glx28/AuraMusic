/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.video

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.auramusic.app.constants.VideoDownloadsKey
import com.auramusic.app.utils.dataStore
import com.auramusic.app.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

/**
 * Videos kept on the device, so they play with no connection.
 *
 * Songs go through the platform download service; a video cannot, because the video
 * player does not read that cache. So a video is fetched here into the app's own files
 * and playback is pointed at those bytes - which means the same file serves both online
 * and offline, and a downloaded video never spends data it does not need to.
 *
 * A video arrives either muxed or as separate video and audio streams. Both are stored;
 * a download is only "ready" once every file it needs has finished, because half a
 * video plays silence and half a video plays a frozen frame.
 */
object VideoDownloadStore {

    private const val DIR_NAME = "video_downloads"

    /** One stored video, as shown on the shelf and recorded for playback. */
    data class Entry(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String?,
        /** Muxed file, when the source was a single stream. */
        val videoFile: String?,
        /** Separate streams, when it was not. Both are required for a ready download. */
        val videoPartFile: String?,
        val audioPartFile: String?,
        val videoMimeType: String?,
        val audioMimeType: String?,
        val height: Int,
        val sizeBytes: Long,
        val durationMs: Long,
        val downloadedAt: Long,
    ) {
        /** Every file this entry needs is on disk and non-empty. */
        fun isComplete(files: File): Boolean {
            val needed = listOfNotNull(videoFile, if (videoFile == null) videoPartFile else null, audioPartFile)
            return needed.isNotEmpty() && needed.all { name ->
                File(files, name).let { it.exists() && it.length() > 0 }
            }
        }
    }

    /** Progress of an in-flight download, keyed by video id. */
    data class Progress(
        val videoId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val done: Boolean,
        val failed: Boolean,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: MutableStateFlow<List<Entry>> = _entries

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: MutableStateFlow<Map<String, Progress>> = _progress

    private val _loaded = MutableStateFlow(false)
    val loaded: Boolean get() = _loaded.value

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(com.auramusic.innertube.YouTube.proxy)
            .proxyAuthenticator { _, response ->
                com.auramusic.innertube.YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .build()
    }

    private fun downloadsDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** Loads the stored list once per process. */
    suspend fun load(context: Context) {
        if (_loaded.value) return
        withContext(Dispatchers.IO) {
            val raw = runCatching { context.dataStore[VideoDownloadsKey] }.getOrNull()
            _entries.value = parse(raw)
            // Drop anything whose files went missing behind our back.
            val dir = downloadsDir(context)
            _entries.value = _entries.value.filter { it.isComplete(dir) }
            _loaded.value = true
        }
    }

    fun isDownloaded(videoId: String): Boolean =
        _entries.value.any { it.videoId == videoId }

    fun entry(videoId: String): Entry? = _entries.value.firstOrNull { it.videoId == videoId }

    fun totalBytes(): Long = _entries.value.sumOf { it.sizeBytes }

    fun progressFor(videoId: String): Progress? = _progress.value[videoId]

    /**
     * Local playback source for a downloaded video, or null when it is not stored.
     * Returning a source with file paths means every player path - online, offline, the
     * overlay, a resumed watch - picks the download up without knowing about it.
     */
    fun localSource(context: Context, videoId: String): com.auramusic.auravideo.AuraVideo.VideoStreamSource? {
        val entry = entry(videoId) ?: return null
        val dir = downloadsDir(context)
        if (!entry.isComplete(dir)) return null
        val asFileUri = { name: String -> File(dir, name).toURI().toString() }
        entry.videoFile?.let { file ->
            return com.auramusic.auravideo.AuraVideo.VideoStreamSource.Single(
                url = asFileUri(file),
                mimeType = entry.videoMimeType ?: "video/mp4",
            )
        }
        val videoPart = entry.videoPartFile ?: return null
        val audioPart = entry.audioPartFile ?: return null
        return com.auramusic.auravideo.AuraVideo.VideoStreamSource.Merged(
            videoUrl = asFileUri(videoPart),
            videoMimeType = entry.videoMimeType ?: "video/mp4",
            audioUrl = asFileUri(audioPart),
            audioMimeType = entry.audioMimeType ?: "audio/mp4",
            height = entry.height,
        )
    }

    /**
     * Fetches the streams into local files. Everything is written to a temporary name and
     * only recorded once complete, so an interrupted download can never be mistaken for a
     * finished one and then play half a video.
     */
    suspend fun download(
        context: Context,
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        source: com.auramusic.auravideo.AuraVideo.VideoStreamSource,
        durationMs: Long = 0,
    ) {
        val ctx = context.applicationContext
        val dir = downloadsDir(ctx)
        val tmpSuffix = ".part"
        val targets: List<Pair<String, String>> = when (source) {
            is com.auramusic.auravideo.AuraVideo.VideoStreamSource.Single ->
                listOf("${videoId}.mp4$tmpSuffix" to source.url)

            is com.auramusic.auravideo.AuraVideo.VideoStreamSource.Merged -> listOf(
                "${videoId}.v$tmpSuffix" to source.videoUrl,
                "${videoId}.a$tmpSuffix" to source.audioUrl,
            )
        }

        _progress.value = _progress.value + (videoId to Progress(videoId, 0, 0, false, false))
        var totalBytes = 0L
        val written = mutableMapOf<String, Long>()
        try {
            for ((name, url) in targets) {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty body")
                    totalBytes += body.contentLength().coerceAtLeast(0)
                    val target = File(dir, name)
                    body.byteStream().use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            var soFar = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                soFar += read
                                written[name] = soFar
                                _progress.value = _progress.value + (
                                    videoId to Progress(
                                        videoId = videoId,
                                        downloadedBytes = written.values.sum(),
                                        totalBytes = if (totalBytes > 0) totalBytes else 0,
                                        done = false,
                                        failed = false,
                                    )
                                    )
                            }
                        }
                    }
                }
            }

            // Rename out of the temporary names only once every file has arrived.
            val finalNames = mutableMapOf<String, String>()
            targets.forEach { (name, _) ->
                val from = File(dir, name)
                val to = File(dir, name.removeSuffix(tmpSuffix))
                if (!from.renameTo(to)) error("Could not finalise ${from.name}")
                finalNames[name] = to.name
            }

            val entry = Entry(
                videoId = videoId,
                title = title,
                channelName = channelName,
                thumbnailUrl = thumbnailUrl,
                videoFile = finalNames[targets.firstOrNull()?.first]
                    ?.takeIf { source is com.auramusic.auravideo.AuraVideo.VideoStreamSource.Single },
                videoPartFile = finalNames["${videoId}.v$tmpSuffix"],
                audioPartFile = finalNames["${videoId}.a$tmpSuffix"],
                videoMimeType = when (source) {
                    is com.auramusic.auravideo.AuraVideo.VideoStreamSource.Single -> source.mimeType
                    is com.auramusic.auravideo.AuraVideo.VideoStreamSource.Merged -> source.videoMimeType
                },
                audioMimeType = (source as? com.auramusic.auravideo.AuraVideo.VideoStreamSource.Merged)?.audioMimeType,
                height = (source as? com.auramusic.auravideo.AuraVideo.VideoStreamSource.Merged)?.height ?: 0,
                sizeBytes = targets.sumOf { File(dir, finalNames[it.first].orEmpty()).length() },
                durationMs = durationMs,
                downloadedAt = System.currentTimeMillis(),
            )
            _entries.value = listOf(entry) + _entries.value.filterNot { it.videoId == videoId }
            persist(ctx)
            _progress.value = _progress.value + (videoId to Progress(videoId, entry.sizeBytes, entry.sizeBytes, true, false))
        } catch (e: Exception) {
            // Nothing half-written is kept: the store only ever holds complete files.
            targets.forEach { (name, _) -> File(dir, name).delete() }
            targets.forEach { (name, _) -> File(dir, name.removeSuffix(tmpSuffix)).delete() }
            Timber.tag("VideoDownloads").w(e, "Download failed for %s", videoId)
            _progress.value = _progress.value + (videoId to Progress(videoId, 0, 0, false, true))
        }
    }

    /** Deletes the files and forgets the entry. */
    suspend fun remove(context: Context, videoId: String) {
        val ctx = context.applicationContext
        withContext(Dispatchers.IO) {
            val dir = downloadsDir(ctx)
            val existing = entry(videoId)
            listOfNotNull(existing?.videoFile, existing?.videoPartFile, existing?.audioPartFile)
                .forEach { File(dir, it).delete() }
            File(dir, "$videoId.mp4.part").delete()
            File(dir, "$videoId.v.part").delete()
            File(dir, "$videoId.a.part").delete()
            _entries.value = _entries.value.filterNot { it.videoId == videoId }
            persist(ctx)
        }
    }

    private suspend fun persist(context: Context) {
        withContext(Dispatchers.IO) {
            val array = org.json.JSONArray()
            _entries.value.forEach { e ->
                array.put(
                    org.json.JSONObject().apply {
                        put("id", e.videoId)
                        put("t", e.title)
                        put("c", e.channelName)
                        put("th", e.thumbnailUrl ?: "")
                        put("f", e.videoFile ?: "")
                        put("pv", e.videoPartFile ?: "")
                        put("pa", e.audioPartFile ?: "")
                        put("mv", e.videoMimeType ?: "")
                        put("ma", e.audioMimeType ?: "")
                        put("h", e.height)
                        put("b", e.sizeBytes)
                        put("d", e.durationMs)
                        put("at", e.downloadedAt)
                    },
                )
            }
            runCatching { context.dataStore.edit { it[VideoDownloadsKey] = array.toString() } }
        }
    }

    private fun parse(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Entry>()
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                out.add(
                    Entry(
                        videoId = o.optString("id"),
                        title = o.optString("t"),
                        channelName = o.optString("c"),
                        thumbnailUrl = o.optString("th").takeIf { it.isNotEmpty() },
                        videoFile = o.optString("f").takeIf { it.isNotEmpty() },
                        videoPartFile = o.optString("pv").takeIf { it.isNotEmpty() },
                        audioPartFile = o.optString("pa").takeIf { it.isNotEmpty() },
                        videoMimeType = o.optString("mv").takeIf { it.isNotEmpty() },
                        audioMimeType = o.optString("ma").takeIf { it.isNotEmpty() },
                        height = o.optInt("h"),
                        sizeBytes = o.optLong("b"),
                        durationMs = o.optLong("d"),
                        downloadedAt = o.optLong("at"),
                    ),
                )
            }
        }
        return out
    }
}