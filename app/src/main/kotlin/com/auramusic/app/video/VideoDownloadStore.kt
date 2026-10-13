/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.video

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.auramusic.app.constants.VideoCodec
import com.auramusic.app.constants.VideoDownloadsKey
import com.auramusic.app.constants.VideoDownloadsPendingKey
import com.auramusic.app.constants.VideoQuality
import com.auramusic.app.di.VideoDownloadStoreEntryPoint
import com.auramusic.app.playback.DownloadUtil
import com.auramusic.app.playback.ExoDownloadService
import com.auramusic.app.utils.dataStore
import com.auramusic.app.utils.get
import com.auramusic.auravideo.AuraVideo
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Videos kept on the device, so they play with no connection.
 *
 * Videos are registered with the same download manager that handles songs rather than
 * being fetched by a loop of their own. That is what puts them in the notification the
 * user already has for downloads - one notification counting songs and videos together -
 * instead of a second one that knows nothing about the first, and it is what makes a
 * video download survive the app being closed, since the work now belongs to a service
 * that keeps running rather than to a coroutine that dies with the process.
 *
 * A video arrives either muxed or as separate video and audio streams, and both shapes
 * are stored as cache keys rather than as files. Playback asks for the key and is handed
 * the cached file, so every route into playback - opening it, resuming it, autoplay -
 * picks the download up without knowing anything about downloads.
 */
@Singleton
class VideoDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadUtil: DownloadUtil,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One stored video, as shown on the shelf and recorded for playback. */
    data class Entry(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String?,
        /**
         * Which keys make up this video: a single muxed one, or a video-only and an
         * audio-only key that have to be merged at playback. A download is only ready
         * once every key it needs has finished - half a video plays silence, and half a
         * video plays a frozen frame.
         */
        val videoKey: String,
        val audioKey: String?,
        val videoMimeType: String?,
        val audioMimeType: String?,
        /** Expected size of each part, so a truncated file is not played as a whole one. */
        val videoBytes: Long,
        val audioBytes: Long,
        val height: Int,
        val quality: VideoQuality,
        val codec: VideoCodec,
        val sizeBytes: Long,
        val durationMs: Long,
        val downloadedAt: Long,
    )

    /**
     * A download that has been asked for but has not arrived.
     *
     * Kept separately from [Entry] because the two answer different questions: an entry
     * is something that can be played, this is something that is on its way. Only the
     * first is playable, but a video that is downloading has to be visible somewhere -
     * otherwise the wait has no face, and the shelf looks empty for the whole of it.
     */
    data class Pending(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String?,
        /** 0 to 1, or null when the stream does not report a length to measure against. */
        val fraction: Float?,
        val failed: Boolean,
    )

    /** Progress of an in-flight download, keyed by video id. */
    data class Progress(
        val videoId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val done: Boolean,
        val failed: Boolean,
    )

    /** What each in-flight video is and what it needs, until it is complete. */
    private data class Record(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String?,
        val parts: List<String>,
        val videoMimeType: String?,
        val audioMimeType: String?,
        val height: Int,
        val quality: VideoQuality,
        val codec: VideoCodec,
        val durationMs: Long,
    ) {
        /** What the notification should say about it. */
        val label: String
            get() = when {
                title.isBlank() -> channelName
                channelName.isBlank() -> title
                else -> "$title \u2022 $channelName"
            }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    private val _records = MutableStateFlow<Map<String, Record>>(emptyMap())
    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    private val _loaded = MutableStateFlow(false)

    private val cache get() = downloadUtil.downloadCache

    init {
        downloadUtil.downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    val parsed = VideoDownloadKeys.parse(download.request.id) ?: return
                    scope.launch {
                        if (download.state == Download.STATE_COMPLETED) {
                            completePart(parsed, download)
                        } else if (download.state == Download.STATE_FAILED) {
                            // A video download that stops has to say why: this is the
                            // only place the reason exists, and without it a failed
                            // download is indistinguishable from one that was cancelled.
                            Timber.tag("VideoDownloads").w(
                                "Download failed for %s (%s): %s",
                                parsed.videoId,
                                parsed.part,
                                finalException?.toString() ?: "no reason given",
                            )
                            _progress.value = _progress.value + (
                                parsed.videoId to Progress(
                                    videoId = parsed.videoId,
                                    downloadedBytes = download.bytesDownloaded,
                                    totalBytes = download.contentLength ?: 0L,
                                    done = false,
                                    failed = true,
                                )
                                )
                        }
                        publishProgress()
                    }
                }
            },
        )

        // Progress is reported by the download manager, so the per-video view of it has
        // to be recomputed whenever any download changes - including songs, which share
        // the manager.
        scope.launch {
            downloadUtil.downloads.collect { publishProgress() }
        }
    }

    private suspend fun load() {
        if (_loaded.value) return
        withContext(Dispatchers.IO) {
            _entries.value = parse(context.dataStore.get(VideoDownloadsKey))
            _records.value = parseRecords(context.dataStore.get(VideoDownloadsPendingKey))
            _loaded.value = true
        }
        // A video whose download finished while the process was gone is complete but
        // unrecorded: the index still holds it, so the entry is written from there.
        reconcileCompleted()
    }

    /**
     * Turns downloads the index reports as finished into entries.
     *
     * Without this a download that completed in the background - the normal case now that
     * the service owns the work - would leave its video unrecorded and therefore played
     * from the network.
     */
    private suspend fun reconcileCompleted() {
        val finished = runCatching {
            downloadUtil.downloadManager.downloadIndex.getDownloads().use { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    ids += cursor.download?.request?.id.orEmpty()
                }
                ids.filter { VideoDownloadKeys.parse(it) != null }
            }
        }.getOrNull().orEmpty()

        for (key in finished) {
            val parsed = VideoDownloadKeys.parse(key) ?: continue
            if (entry(parsed.videoId) != null) continue
            val state = runCatching {
                downloadUtil.downloadManager.downloadIndex.getDownload(key)
            }.getOrNull() ?: continue
            if (state.state == Download.STATE_COMPLETED) completePart(parsed, state)
        }
        publishProgress()
    }

    fun isDownloaded(videoId: String): Boolean = entry(videoId) != null

    fun entry(videoId: String): Entry? = _entries.value.firstOrNull { it.videoId == videoId }

    fun totalBytes(): Long = _entries.value.sumOf { it.sizeBytes }

    fun progressFor(videoId: String): Progress? = _progress.value[videoId]

    /**
     * The file behind a cache key, or null when it is not wholly there.
     *
     * The cache holds a key as spans, and a half-written download is a span that stops
     * early. Handing playback that file would produce a video that ends before the
     * storyboard says it should, so the length has to match what the download reported.
     */
    private fun cachedFile(key: String, expectedBytes: Long): File? {
        val spans = cache.getCachedSpans(key)
        if (spans.size != 1) return null
        val span = spans.first()
        if (!span.isCached) return null
        if (expectedBytes > 0L && span.length != expectedBytes) return null
        return span.file?.takeIf { it.exists() && it.length() == span.length }
    }

    /**
     * Local playback source for a downloaded video, or null when it is not stored.
     *
     * Handing back file paths means every player path picks the download up without
     * knowing about it. The cache is asked rather than the recorded entry, so a video
     * whose bytes were cleared out from under us is treated as absent instead of being
     * handed a path that no longer resolves.
     */
    fun localSource(videoId: String): AuraVideo.VideoStreamSource? {
        val entry = entry(videoId) ?: return null
        val videoFile = cachedFile(entry.videoKey, entry.videoBytes) ?: return null
        val videoUrl = Uri.fromFile(videoFile).toString()

        val audioKey = entry.audioKey ?: return AuraVideo.VideoStreamSource.Single(
            url = videoUrl,
            mimeType = entry.videoMimeType ?: "video/mp4",
        )

        val audioFile = cachedFile(audioKey, entry.audioBytes) ?: return null
        return AuraVideo.VideoStreamSource.Merged(
            videoUrl = videoUrl,
            videoMimeType = entry.videoMimeType ?: "video/mp4",
            audioUrl = Uri.fromFile(audioFile).toString(),
            audioMimeType = entry.audioMimeType ?: "audio/mp4",
            height = entry.height,
        )
    }

    /**
     * Registers a download for a video at the chosen quality and codec.
     *
     * The shape of the stream is resolved first because it decides how many downloads
     * there are: a muxed stream is one file, a separate video and audio pair is two, and
     * only one of the two answers can be played. The choice travels in the cache key, so
     * a download that is resumed after the process has gone still knows what it was
     * meant to fetch.
     */
    fun download(
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        source: AuraVideo.VideoStreamSource,
        quality: VideoQuality,
        codec: VideoCodec,
        durationMs: Long,
    ) {
        val parts = when (source) {
            is AuraVideo.VideoStreamSource.Single -> listOf(
                VideoDownloadPart(VideoDownloadKeys.PART_MUXED, source.url, source.mimeType),
            )

            is AuraVideo.VideoStreamSource.Merged -> listOf(
                VideoDownloadPart(VideoDownloadKeys.PART_VIDEO, source.videoUrl, source.videoMimeType),
                VideoDownloadPart(VideoDownloadKeys.PART_AUDIO, source.audioUrl, source.audioMimeType),
            )
        }

        val record = Record(
            videoId = videoId,
            title = title,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            parts = parts.map { it.part },
            videoMimeType = parts.first { it.part != VideoDownloadKeys.PART_AUDIO }.mimeType,
            audioMimeType = parts.firstOrNull { it.part == VideoDownloadKeys.PART_AUDIO }?.mimeType,
            height = (source as? AuraVideo.VideoStreamSource.Merged)?.height ?: 0,
            quality = quality,
            codec = codec,
            durationMs = durationMs,
        )
        // Recorded before the transfer starts, because the transfer belongs to a service
        // that may outlive this process - and what arrives when it does has to be able
        // to say what it is.
        _records.value = _records.value + (videoId to record)

        scope.launch {
            persistRecords()
            parts.forEach { part ->
                val key = VideoDownloadKeys.key(videoId, part.part, quality, codec)
                val request = DownloadRequest.Builder(key, Uri.parse(part.url))
                    .setCustomCacheKey(key)
                    .setMimeType(part.mimeType)
                    // What the download service reads straight into the notification.
                    // It used to carry the whole record, which meant the notification
                    // showed a page of fields in place of a title.
                    .setData(record.label.toByteArray())
                    .build()
                // Not foregrounded: the download service already holds the notification
                // for everything in flight, and asking for a second one replaces it.
                DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
            }
        }
    }

    /**
     * Starts a failed download again, at the quality and codec it was asked for.
     *
     * Re-resolved rather than replayed: the URLs a failed attempt was given expire, and
     * a restart that reuses them fails again for the same reason and looks like the app
     * refusing to try.
     */
    fun retry(videoId: String, onFailed: () -> Unit = {}) {
        val record = _records.value[videoId] ?: return
        scope.launch {
            val source = com.auramusic.app.utils.AuraPlayerUtils
                .getVideoStreamSource(videoId, record.quality, record.codec)
                .getOrNull()
            if (source == null) {
                onFailed()
                return@launch
            }
            download(
                videoId = record.videoId,
                title = record.title,
                channelName = record.channelName,
                thumbnailUrl = record.thumbnailUrl,
                source = source,
                quality = record.quality,
                codec = record.codec,
                durationMs = record.durationMs,
            )
        }
    }

    /** Takes a video off the device, including anything still downloading. */
    fun remove(videoId: String) {
        val entry = entry(videoId)
        listOfNotNull(entry?.videoKey, entry?.audioKey).forEach { key ->
            DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, key, false)
        }
        // A download that never finished has no entry, so its keys are derived instead:
        // without this a half-finished download would keep running with nothing left to
        // show for it.
        VideoDownloadKeys.allKeysFor(videoId).forEach { key ->
            DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, key, false)
        }
        scope.launch {
            _entries.value = _entries.value.filterNot { it.videoId == videoId }
            _records.value = _records.value - videoId
            _progress.value = _progress.value - videoId
            persist()
            persistRecords()
            publishProgress()
        }
    }

    /**
     * Records a video once every part of it has arrived.
     *
     * Nothing is written until all of it is there: a recorded entry is what playback
     * treats as playable, and one recorded early would send the player to a file that is
     * still growing.
     */
    private suspend fun completePart(parsed: VideoDownloadKeys.Parsed, download: Download) {
        val record = _records.value[parsed.videoId] ?: return
        if (parsed.part !in record.parts) return

        val index = downloadUtil.downloadManager.downloadIndex
        val sizes = record.parts.associateWith { part ->
            val key = VideoDownloadKeys.key(record.videoId, part, record.quality, record.codec)
            val state = runCatching { index.getDownload(key) }.getOrNull()
            state?.takeIf { it.state == Download.STATE_COMPLETED }?.contentLength ?: 0L
        }
        // Every part present at its full length: anything less is still arriving, and a
        // record written early would send the player to a file that is still growing.
        if (sizes.values.any { it <= 0L }) return
        if (record.parts.any { part ->
                val key = VideoDownloadKeys.key(record.videoId, part, record.quality, record.codec)
                cachedFile(key, sizes[part] ?: 0L) == null
            }
        ) {
            return
        }

        val videoPart = VideoDownloadKeys.PART_VIDEO.takeIf { it in record.parts } ?: VideoDownloadKeys.PART_MUXED
        val entry = Entry(
            videoId = record.videoId,
            title = record.title,
            channelName = record.channelName,
            thumbnailUrl = record.thumbnailUrl,
            videoKey = VideoDownloadKeys.key(record.videoId, videoPart, record.quality, record.codec),
            audioKey = if (VideoDownloadKeys.PART_AUDIO in record.parts) {
                VideoDownloadKeys.key(record.videoId, VideoDownloadKeys.PART_AUDIO, record.quality, record.codec)
            } else {
                null
            },
            videoMimeType = record.videoMimeType,
            audioMimeType = record.audioMimeType,
            videoBytes = sizes[videoPart] ?: 0L,
            audioBytes = sizes[VideoDownloadKeys.PART_AUDIO] ?: 0L,
            height = record.height,
            quality = record.quality,
            codec = record.codec,
            sizeBytes = sizes.values.sum(),
            durationMs = record.durationMs,
            downloadedAt = System.currentTimeMillis(),
        )
        _entries.value = listOf(entry) + _entries.value.filterNot { it.videoId == entry.videoId }
        _records.value = _records.value - entry.videoId
        persist()
        persistRecords()
    }

    /**
     * Republishes progress as one entry per video.
     *
     * A video is often two downloads, so its progress is the sum of the parts - otherwise
     * the bar would restart at zero every time the second part began.
     */
    private fun publishProgress() {
        val byVideo = downloadUtil.downloads.value
            .mapNotNull { (key, download) -> VideoDownloadKeys.parse(key)?.let { it to download } }
            .groupBy({ it.first.videoId }, { it.second })

        val next = mutableMapOf<String, Progress>()
        byVideo.forEach { (videoId, downloads) ->
            next[videoId] = Progress(
                videoId = videoId,
                downloadedBytes = downloads.sumOf { it.bytesDownloaded },
                totalBytes = downloads.sumOf { it.contentLength ?: 0L },
                done = downloads.isNotEmpty() && downloads.all { it.state == Download.STATE_COMPLETED },
                failed = downloads.any { it.state == Download.STATE_FAILED },
            )
        }
        // Built from what is finished plus what is in flight, rather than from the last
        // map with entries merged in. Carrying entries forward is what left a download
        // that had failed or been cancelled reporting the progress it had reached when
        // it stopped - frozen at zero, as though it were still on its way.
        _progress.value =
            _entries.value.associate { entry ->
                entry.videoId to Progress(
                    videoId = entry.videoId,
                    downloadedBytes = entry.sizeBytes,
                    totalBytes = entry.sizeBytes,
                    done = true,
                    failed = false,
                )
            } + next
    }

    private suspend fun persist() {
        withContext(Dispatchers.IO) {
            val array = org.json.JSONArray()
            _entries.value.forEach { e ->
                array.put(
                    org.json.JSONObject().apply {
                        put("id", e.videoId)
                        put("vk", e.videoKey)
                        put("ak", e.audioKey ?: "")
                        put("t", e.title)
                        put("c", e.channelName)
                        put("th", e.thumbnailUrl ?: "")
                        put("mv", e.videoMimeType ?: "")
                        put("ma", e.audioMimeType ?: "")
                        put("vb", e.videoBytes)
                        put("ab", e.audioBytes)
                        put("h", e.height)
                        put("q", e.quality.name)
                        put("k", e.codec.name)
                        put("b", e.sizeBytes)
                        put("d", e.durationMs)
                        put("at", e.downloadedAt)
                    },
                )
            }
            runCatching { context.dataStore.edit { it[VideoDownloadsKey] = array.toString() } }
        }
    }

    /** Downloads still on their way, as the shelves show them. */
    fun pending(): List<Pending> = _records.value.values.map { record ->
        val progress = _progress.value[record.videoId]
        Pending(
            videoId = record.videoId,
            title = record.title,
            channelName = record.channelName,
            thumbnailUrl = record.thumbnailUrl,
            // Left out entirely when there is no length to measure against: a ring that
            // jumps about between ticks is worse than one that simply turns.
            fraction = progress
                ?.takeIf { it.totalBytes > 0 && !it.done && !it.failed }
                ?.let { (it.downloadedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f) },
            failed = progress?.failed == true,
        )
    }

    private suspend fun persistRecords() {
        withContext(Dispatchers.IO) {
            val array = org.json.JSONArray()
            _records.value.values.forEach { record ->
                array.put(
                    org.json.JSONObject().apply {
                        put("id", record.videoId)
                        put("t", record.title)
                        put("c", record.channelName)
                        put("th", record.thumbnailUrl ?: "")
                        put("p", record.parts.joinToString(","))
                        put("mv", record.videoMimeType ?: "")
                        put("ma", record.audioMimeType ?: "")
                        put("h", record.height)
                        put("q", record.quality.name)
                        put("k", record.codec.name)
                        put("d", record.durationMs)
                    },
                )
            }
            runCatching { context.dataStore.edit { it[VideoDownloadsPendingKey] = array.toString() } }
        }
    }

    private fun parseRecords(raw: String?): Map<String, Record> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = mutableMapOf<String, Record>()
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val videoId = o.optString("id")
                val partsArray = o.optJSONArray("p")
                val parts = (0 until (partsArray?.length() ?: 0)).mapNotNull {
                    partsArray.optString(it).takeIf(String::isNotEmpty)
                }
                // A record whose parts are not listed cannot be turned into a finished
                // entry, so there is no point keeping it.
                if (videoId.isEmpty() || parts.isEmpty()) continue
                out[videoId] = Record(
                    videoId = videoId,
                    title = o.optString("t"),
                    channelName = o.optString("c"),
                    thumbnailUrl = o.optString("th").takeIf { it.isNotEmpty() },
                    parts = parts,
                    videoMimeType = o.optString("mv").takeIf { it.isNotEmpty() },
                    audioMimeType = o.optString("ma").takeIf { it.isNotEmpty() },
                    height = o.optInt("h"),
                    quality = VideoQuality.fromName(o.optString("q")),
                    codec = VideoCodec.fromName(o.optString("k")),
                    durationMs = o.optLong("d"),
                )
            }
        }
        return out
    }

    private fun parse(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Entry>()
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                // An entry without a cache key predates the download service being used
                // for videos, when the bytes lived in files of their own. Those files
                // are no longer read, so keeping the record would only show a video that
                // plays from the network while claiming to be downloaded.
                val videoKey = o.optString("vk").takeIf { it.isNotEmpty() } ?: continue
                out.add(
                    Entry(
                        videoId = o.optString("id"),
                        title = o.optString("t"),
                        channelName = o.optString("c"),
                        thumbnailUrl = o.optString("th").takeIf { it.isNotEmpty() },
                        videoKey = videoKey,
                        audioKey = o.optString("ak").takeIf { it.isNotEmpty() },
                        videoMimeType = o.optString("mv").takeIf { it.isNotEmpty() },
                        audioMimeType = o.optString("ma").takeIf { it.isNotEmpty() },
                        videoBytes = o.optLong("vb"),
                        audioBytes = o.optLong("ab"),
                        height = o.optInt("h"),
                        quality = VideoQuality.fromName(o.optString("q")),
                        codec = VideoCodec.fromName(o.optString("k")),
                        sizeBytes = o.optLong("b"),
                        durationMs = o.optLong("d"),
                        downloadedAt = o.optLong("at"),
                    ),
                )
            }
        }
        // A recorded video whose bytes are gone is not downloaded; dropping it keeps the
        // shelf honest about what is on the device.
        return out.filter { cachedFile(it.videoKey, it.videoBytes) != null }
    }

    /** One stream half of a download: which part it is, where it comes from, what it is. */
    private data class VideoDownloadPart(
        val part: String,
        val url: String,
        val mimeType: String,
    )

    companion object {
        /**
         * The store, resolved from a context.
         *
         * Playback needs to ask whether a video is on the device before it resolves a
         * stream, and it has no injected dependencies at that point - so the instance is
         * looked up once and reused.
         */
        @Volatile
        private var instance: VideoDownloadStore? = null

        fun get(context: Context): VideoDownloadStore =
            instance ?: synchronized(this) {
                instance ?: EntryPointAccessors
                    .fromApplication(
                        context.applicationContext,
                        VideoDownloadStoreEntryPoint::class.java,
                    )
                    .videoDownloadStore()
                    .also { instance = it }
            }

        suspend fun load(context: Context) = get(context).load()

        fun localSource(context: Context, videoId: String): AuraVideo.VideoStreamSource? =
            get(context).localSource(videoId)

        fun isDownloaded(context: Context, videoId: String): Boolean =
            get(context).isDownloaded(videoId)

        fun entries(context: Context): StateFlow<List<Entry>> = get(context)._entries

        fun progress(context: Context): StateFlow<Map<String, Progress>> = get(context)._progress

        fun progressFor(context: Context, videoId: String): Progress? =
            get(context).progressFor(videoId)

        fun pending(context: Context): List<Pending> = get(context).pending()

        fun retry(context: Context, videoId: String, onFailed: () -> Unit = {}) =
            get(context).retry(videoId, onFailed)

        fun download(context: Context, videoId: String, title: String, channelName: String, thumbnailUrl: String?, source: AuraVideo.VideoStreamSource, quality: VideoQuality, codec: VideoCodec, durationMs: Long) =
            get(context).download(videoId, title, channelName, thumbnailUrl, source, quality, codec, durationMs)
    }
}

/**
 * How a video download is named in the download cache.
 *
 * The key carries everything a resumed download needs - which part of the stream it is,
 * and at what quality and codec - because by the time it resumes the object that asked
 * for it no longer exists, and a key that only said "video 123" could not be resolved
 * into a URL at all.
 */
object VideoDownloadKeys {
    const val PREFIX = "video:"

    /** Muxed: one file carrying both. */
    const val PART_MUXED = "m"

    /** Video-only half of a split stream. */
    const val PART_VIDEO = "v"

    /** Audio-only half of a split stream. */
    const val PART_AUDIO = "a"

    data class Parsed(
        val videoId: String,
        val part: String,
        val quality: VideoQuality,
        val codec: VideoCodec,
    )

    fun key(videoId: String, part: String, quality: VideoQuality, codec: VideoCodec): String =
        "$PREFIX$videoId|$part|${quality.name}|${codec.name}"

    fun parse(key: String): Parsed? {
        if (!key.startsWith(PREFIX)) return null
        val parts = key.removePrefix(PREFIX).split("|")
        if (parts.size != 4) return null
        return Parsed(
            videoId = parts[0],
            part = parts[1],
            quality = VideoQuality.fromName(parts[2]),
            codec = VideoCodec.fromName(parts[3]),
        )
    }

    /**
     * Every part a video could have been registered under, for a download that never
     * finished and so has no recorded entry to enumerate.
     */
    fun allKeysFor(videoId: String): List<String> =
        VideoQuality.entries.flatMap { quality ->
            VideoCodec.entries.flatMap { codec ->
                listOf(PART_MUXED, PART_VIDEO, PART_AUDIO).map { key(videoId, it, quality, codec) }
            }
        }
}
