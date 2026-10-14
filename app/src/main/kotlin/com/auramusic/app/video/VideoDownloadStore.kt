/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.video

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.auramusic.app.constants.VideoCodec
import com.auramusic.app.constants.VideoDownloadsKey
import com.auramusic.app.constants.VideoQuality
import com.auramusic.app.constants.VideoDownloadsPendingKey
import com.auramusic.app.di.VideoDownloadStoreEntryPoint
import com.auramusic.app.utils.AuraPlayerUtils
import com.auramusic.app.utils.dataStore
import com.auramusic.app.utils.get
import com.auramusic.auravideo.AuraVideo
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Videos kept on the device, so they play with no connection.
 *
 * Videos are not handed to the download service that handles songs. That service is a
 * playlist tool: it is told to keep a song, and it does, and it is very good at it. A
 * video is not that - it arrives as separate video and audio streams that have to be
 * brought back together, it has to be reported on by name while it moves, and it has to
 * keep moving when the app is closed. Filing it as though it were a song made every one
 * of those things somebody else's problem, and the video was the one that stopped
 * working.
 *
 * So a video has its own [VideoDownloadService], and this is the record of what it was
 * asked for and what it ended up with. The bytes themselves live in files the service
 * writes; playback is handed those files, so every route into playback - opening it,
 * resuming it, autoplay - finds the download without knowing that downloads exist.
 */
@Singleton
class VideoDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** One stored video, as shown on the shelf and recorded for playback. */
    data class Entry(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String?,
        /** Muxed file, when the source was a single stream. */
        val videoFile: String?,
        /** Separate streams, when it was not. Both are needed for a ready download. */
        val videoPartFile: String?,
        val audioPartFile: String?,
        val videoMimeType: String?,
        val audioMimeType: String?,
        val height: Int,
        val quality: VideoQuality,
        val codec: VideoCodec,
        val sizeBytes: Long,
        val durationMs: Long,
        val downloadedAt: Long,
    ) {
        /** Every file this entry needs is on disk and non-empty. */
        fun isComplete(dir: File): Boolean {
            val needed = listOfNotNull(
                videoFile,
                if (videoFile == null) videoPartFile else null,
                audioPartFile,
            )
            return needed.isNotEmpty() && needed.all { name ->
                File(dir, name).let { it.exists() && it.length() > 0 }
            }
        }
    }

    /** What was asked for, before a single byte has moved. */
    data class Request(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String?,
        val quality: VideoQuality,
        val codec: VideoCodec,
        val durationMs: Long,
    )

    /** Progress of an in-flight download, keyed by video id. */
    data class Progress(
        val videoId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val done: Boolean,
        val failed: Boolean,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    private val _requests = MutableStateFlow<Map<String, Request>>(emptyMap())
    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    private val _loaded = MutableStateFlow(false)

    // Declared with the state it serves: [pendingFlow] is built from it, and every
    // write that outlives the screen that asked for it happens on it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun downloadsDir(): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    suspend fun load() {
        if (_loaded.value) return
        withContext(Dispatchers.IO) {
            val dir = downloadsDir()
            _entries.value = parse(context.dataStore.get(VideoDownloadsKey))
                .filter { it.isComplete(dir) }
            _requests.value = parseRequests(context.dataStore.get(VideoDownloadsPendingKey))
            _loaded.value = true
        }
    }

    fun isDownloaded(videoId: String): Boolean = entry(videoId) != null

    fun entry(videoId: String): Entry? = _entries.value.firstOrNull { it.videoId == videoId }

    fun request(videoId: String): Request? = _requests.value[videoId]

    fun totalBytes(): Long = _entries.value.sumOf { it.sizeBytes }

    /**
     * Everything the video downloads occupy on the device right now.
     *
     * Measured off the folder rather than off the recorded entries, so a transfer still
     * arriving and a file left behind by an entry that was deleted are both counted -
     * the question storage settings asks is how much room the videos are taking, and
     * that is the space they are taking.
     */
    fun diskUsage(): Long = runCatching {
        downloadsDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    fun progressFor(videoId: String): Progress? = _progress.value[videoId]

    /**
     * Local playback source for a downloaded video, or null when it is not stored.
     *
     * Handing back file paths means every player path picks the download up without
     * knowing about it. The files are asked about rather than the recorded entry, so a
     * video whose bytes were cleared out from under us is treated as absent instead of
     * being handed a path that no longer resolves.
     */
    fun localSource(videoId: String): AuraVideo.VideoStreamSource? {
        val entry = entry(videoId) ?: return null
        val dir = downloadsDir()
        if (!entry.isComplete(dir)) return null
        val asUri = { name: String -> Uri.fromFile(File(dir, name)).toString() }

        entry.videoFile?.let { file ->
            return AuraVideo.VideoStreamSource.Single(
                url = asUri(file),
                mimeType = entry.videoMimeType ?: "video/mp4",
            )
        }
        val videoPart = entry.videoPartFile ?: return null
        val audioPart = entry.audioPartFile ?: return null
        return AuraVideo.VideoStreamSource.Merged(
            videoUrl = asUri(videoPart),
            videoMimeType = entry.videoMimeType ?: "video/mp4",
            audioUrl = asUri(audioPart),
            audioMimeType = entry.audioMimeType ?: "audio/mp4",
            height = entry.height,
        )
    }

    /**
     * Records what was asked for, before the transfer starts.
     *
     * Written down rather than held in memory because the transfer belongs to a service
     * that outlives the process, and a request that only existed in the caller's head is
     * a request the service cannot describe in its notification after the caller is gone.
     */
    suspend fun enqueue(request: Request) {
        _requests.value = _requests.value + (request.videoId to request)
        _progress.value = _progress.value + (
            request.videoId to Progress(request.videoId, 0, 0, done = false, failed = false)
            )
        persistRequests()
    }

    /** Progress as the service reports it. */
    fun updateProgress(videoId: String, downloadedBytes: Long, totalBytes: Long, failed: Boolean = false) {
        _progress.value = _progress.value + (
            videoId to Progress(
                videoId = videoId,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                done = false,
                failed = failed,
            )
            )
    }

    /**
     * Records a video once every file it needs has arrived.
     *
     * Nothing is written until all of it is there: a recorded entry is what playback
     * treats as playable, and one written early would send the player to a file that is
     * still growing.
     */
    suspend fun complete(entry: Entry) {
        val dir = downloadsDir()
        if (!entry.isComplete(dir)) return
        _entries.value = listOf(entry) + _entries.value.filterNot { it.videoId == entry.videoId }
        _requests.value = _requests.value - entry.videoId
        _progress.value = _progress.value + (
            entry.videoId to Progress(
                entry.videoId,
                entry.sizeBytes,
                entry.sizeBytes,
                done = true,
                failed = false,
            )
            )
        persist()
        persistRequests()
    }

    /** A download that stopped, so the shelf can offer to try again rather than hang. */
    fun markFailed(videoId: String) {
        val current = _progress.value[videoId] ?: return
        _progress.value = _progress.value + (
            videoId to current.copy(failed = true, done = false)
            )
    }

    /** Downloads still on their way, as the screens show them. */
    fun pending(): List<Pending> = buildPending(_requests.value, _progress.value)

    /** One download, as the screens show it, or null when it is not one any more. */
    private fun buildPending(
        request: Request,
        state: Progress?,
        arrived: Set<String>,
    ): Pending? {
        // Asked for and already finished: it is an entry now, not something pending.
        if (request.videoId in arrived) return null
        return Pending(
            videoId = request.videoId,
            title = request.title,
            channelName = request.channelName,
            thumbnailUrl = request.thumbnailUrl,
            // Left out when there is no length to measure against: a ring that jumps
            // between ticks is worse than one that simply turns.
            fraction = state
                ?.takeIf { it.totalBytes > 0 && !it.done && !it.failed }
                ?.let { (it.downloadedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f) },
            failed = state?.failed == true,
            downloadedBytes = state?.downloadedBytes ?: 0L,
            totalBytes = state?.totalBytes ?: 0L,
        )
    }

    private fun buildPending(
        requests: Map<String, Request>,
        progress: Map<String, Progress>,
        entries: List<Entry> = _entries.value,
    ): List<Pending> {
        val arrived = entries.mapTo(mutableSetOf()) { it.videoId }
        return requests.values.mapNotNull { request ->
            buildPending(request, progress[request.videoId], arrived)
        }
    }

    /**
     * The same list, as something a screen can watch.
     *
     * [pending] is a plain call, which left the downloads screen polling once a second
     * to notice progress the service was already reporting. This is the same answer,
     * delivered when it changes - so a bar moves as the bytes land rather than once a
     * second whether or not anything happened.
     */
    val pendingFlow: StateFlow<List<Pending>> =
        combine(_requests, _progress, _entries) { requests, progress, entries ->
            buildPending(requests, progress, entries)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Starts a failed download again, at the quality and codec it was asked for.
     *
     * Re-resolved rather than replayed: the URLs a failed attempt was given expire, and
     * a restart that reuses them fails again for the same reason and looks like the app
     * refusing to try.
     */
    fun retry(videoId: String) {
        val request = _requests.value[videoId] ?: return
        scope.launch {
            enqueue(request)
            startServiceOnMainThread(videoId)
        }
    }

    /**
     * Records what was asked for and hands the work to the service.
     *
     * The record is written first because the service may outlive this process, and a
     * request that only existed in the caller's head is one it cannot name in its
     * notification once the caller is gone.
     */
    fun download(request: Request) {
        scope.launch {
            enqueue(request)
            startServiceOnMainThread(request.videoId)
        }
    }

    /**
     * Starts the download service from the main thread.
     *
     * The service is started in the foreground, and the system counts that window from
     * the call - not from the moment the service is created. Asking for it from a
     * background dispatcher spends part of that window in a scheduler hop and part of
     * it waiting for a main thread that may be busy, and the service is killed for
     * missing the deadline. The call itself is cheap; it is the thread it is made from
     * that matters.
     */
    private suspend fun startServiceOnMainThread(videoId: String) {
        withContext(Dispatchers.Main.immediate) {
            VideoDownloadService.start(context, videoId)
        }
    }


    /** Takes a video off the device, including anything still downloading. */
    fun remove(videoId: String) {
        val entry = entry(videoId)
        val request = request(videoId)
        val dir = downloadsDir()
        listOfNotNull(
            entry?.videoFile,
            entry?.videoPartFile,
            entry?.audioPartFile,
            // A download that never finished has no entry, so its names are derived.
            request?.let { "${it.videoId}.mp4" },
            request?.let { "${it.videoId}.v" },
            request?.let { "${it.videoId}.a" },
        ).forEach { File(dir, it).delete() }
        listOf("${videoId}.mp4.part", "${videoId}.v.part", "${videoId}.a.part")
            .forEach { File(dir, it).delete() }

        _entries.value = _entries.value.filterNot { it.videoId == videoId }
        _requests.value = _requests.value - videoId
        _progress.value = _progress.value - videoId
        scope.launch {
            persist()
            persistRequests()
        }
    }

    /**
     * Takes every video off the device.
     *
     * Used by storage settings, where the question is "how do I get this space back"
     * rather than "remove that one video". Anything half-written goes with it: a file
     * that was never finished was never playable, so it is only taking up room.
     */
    fun removeEverything() {
        runCatching {
            downloadsDir().listFiles()?.forEach { it.deleteRecursively() }
        }
        _entries.value = emptyList()
        _requests.value = emptyMap()
        _progress.value = emptyMap()
        scope.launch {
            persist()
            persistRequests()
        }
    }

    private suspend fun persist() {
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
                        quality = VideoQuality.fromName(o.optString("q")),
                        codec = VideoCodec.fromName(o.optString("k")),
                        sizeBytes = o.optLong("b"),
                        durationMs = o.optLong("d"),
                        downloadedAt = o.optLong("at"),
                    ),
                )
            }
        }
        return out
    }

    private suspend fun persistRequests() {
        withContext(Dispatchers.IO) {
            val array = org.json.JSONArray()
            _requests.value.values.forEach { request ->
                array.put(
                    org.json.JSONObject().apply {
                        put("id", request.videoId)
                        put("t", request.title)
                        put("c", request.channelName)
                        put("th", request.thumbnailUrl ?: "")
                        put("q", request.quality.name)
                        put("k", request.codec.name)
                        put("d", request.durationMs)
                    },
                )
            }
            runCatching { context.dataStore.edit { it[VideoDownloadsPendingKey] = array.toString() } }
        }
    }

    private fun parseRequests(raw: String?): Map<String, Request> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = mutableMapOf<String, Request>()
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val videoId = o.optString("id")
                if (videoId.isEmpty()) continue
                out[videoId] = Request(
                    videoId = videoId,
                    title = o.optString("t"),
                    channelName = o.optString("c"),
                    thumbnailUrl = o.optString("th").takeIf { it.isNotEmpty() },
                    quality = VideoQuality.fromName(o.optString("q")),
                    codec = VideoCodec.fromName(o.optString("k")),
                    durationMs = o.optLong("d"),
                )
            }
        }
        return out
    }

    /**
     * A download that has been asked for but has not arrived.
     *
     * Kept apart from [Entry] because the two answer different questions: an entry is
     * something that can be played, this is something that is on its way. Only the first
     * is playable, but a video that is downloading has to be visible somewhere.
     */
    data class Pending(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String?,
        val fraction: Float?,
        val failed: Boolean,
        /** What has landed so far, so a row can say how much rather than only how much. */
        val downloadedBytes: Long = 0L,
        /** The length the service was told to expect, 0 when nobody would say. */
        val totalBytes: Long = 0L,
    )

    companion object {
        /** Where video bytes live. Under the app's own files, as audio downloads are not. */
        const val DIR_NAME = "video_downloads"

        /**
         * The store, resolved from a context.
         *
         * Playback has to ask whether a video is on the device before it resolves a
         * stream, and has no injected dependencies at that point - so the instance is
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

        fun entries(context: Context): StateFlow<List<Entry>> = get(context)._entries

        fun progress(context: Context): StateFlow<Map<String, Progress>> = get(context)._progress

        fun pending(context: Context): List<Pending> = get(context).pending()

        fun pendingFlow(context: Context): StateFlow<List<Pending>> = get(context).pendingFlow

        /** Videos occupying this much room on the device, finished or still arriving. */
        fun diskUsage(context: Context): Long = get(context).diskUsage()

        /** Takes every video off the device, including the ones still on their way. */
        fun removeAll(context: Context) {
            val store = get(context)
            // Anything still moving has to be stopped first: the files are about to go,
            // and a service still writing them would put them back.
            store.pending().forEach { VideoDownloadService.cancel(context, it.videoId) }
            store.removeEverything()
        }

        fun download(context: Context, request: Request) = get(context).download(request)

        fun retry(context: Context, videoId: String) = get(context).retry(videoId)

        fun cancel(context: Context, videoId: String) {
            VideoDownloadService.cancel(context, videoId)
            get(context).remove(videoId)
        }
    }
}
