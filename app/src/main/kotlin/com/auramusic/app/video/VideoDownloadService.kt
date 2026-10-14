/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.video

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.Util
import com.auramusic.app.MainActivity
import com.auramusic.app.R
import com.auramusic.app.utils.AuraPlayerUtils
import com.auramusic.auravideo.AuraVideo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import java.io.File

/**
 * Fetches videos onto the device, and says so while it does.
 *
 * Songs are kept by the download service that handles playlists, which is the right tool
 * for a song and the wrong one for a video: a video arrives as separate video and audio
 * streams that have to be brought back together, it has to be named in its notification
 * rather than counted, and it has to keep going when the app is closed. None of that is
 * what that service is for, and filing a video as though it were a song is what left the
 * transfer showing nothing and never finishing.
 *
 * So this is a service of its own. It runs in the foreground for as long as there is
 * something to fetch - which is what the notification is for - writes the streams to
 * files, and records the result only once every file has arrived.
 */
@AndroidEntryPoint
class VideoDownloadService : android.app.Service() {

    @Inject
    lateinit var store: VideoDownloadStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    /** What the ongoing notification last said, so it is not rebuilt for nothing. */
    @Volatile private var lastNotifiedPercent: Int = -1
    @Volatile private var lastNotifiedBytes: Long = 0L

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // The service outlives the screen that asked for a download, so the record of
        // what was asked for is read here rather than being carried in the intent: a
        // restarted process has the intent but not the store in memory.
        scope.launch { store.load() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> intent.getStringExtra(EXTRA_VIDEO_ID)?.let { enqueue(it) }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_VIDEO_ID)?.let { cancel(it) }
            ACTION_CANCEL_ALL -> {
                jobs.keys.toList().forEach { cancel(it) }
            }
            // Nothing to resume into: a restart with no job would have nothing to fetch,
            // and a half-written file is discarded rather than resumed - half a video
            // plays silence and half a video plays a frozen frame. The service still has
            // to reach the foreground to be allowed to say so: one started in the
            // foreground and left without it is killed by the system, not stopped.
            else -> {
                promoteForeground(
                    videoId = intent?.getStringExtra(EXTRA_VIDEO_ID).orEmpty(),
                    downloaded = 0L,
                    total = 0L,
                )
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enqueue(videoId: String) {
        if (jobs.containsKey(videoId)) return
        // Promoted before the work starts rather than once the first byte is written.
        // The window between startForegroundService() and the first update is short,
        // and a fetch that spends it resolving its streams gets killed for it.
        promoteForeground(videoId, downloaded = 0L, total = 0L)
        val job = scope.launch { runDownload(videoId) }
        jobs[videoId] = job
        job.invokeOnCompletion {
            scope.launch {
                jobs.remove(videoId)
                if (jobs.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    // One is still moving, so the notification keeps describing the
                    // transfer rather than stopping at a video that has finished.
                    updateForeground()
                }
            }
        }
    }

    private fun cancel(videoId: String) {
        jobs.remove(videoId)?.cancel()
        store.remove(videoId)
    }

    private suspend fun runDownload(videoId: String) {
        val request = store.request(videoId)
        if (request == null) {
            Timber.tag(TAG).w("No record of what %s was meant to be", videoId)
            return
        }
        val dir = File(filesDir, VideoDownloadStore.DIR_NAME).apply { if (!exists()) mkdirs() }

        try {
            val source = AuraPlayerUtils
                .getVideoStreamSource(videoId, request.quality, request.codec)
                .getOrThrow()

            val parts = when (source) {
                is AuraVideo.VideoStreamSource.Single -> listOf(
                    Part("${videoId}.mp4", source.url),
                )

                is AuraVideo.VideoStreamSource.Merged -> listOf(
                    Part("${videoId}.v", source.videoUrl),
                    Part("${videoId}.a", source.audioUrl),
                )
            }

            // How big the video is has to be known before the first byte is written, or
            // there is nothing for a percentage to be a percentage of. The lengths are
            // asked for up front - one cheap request per part, answered from headers
            // and closed again - so the bar moves smoothly from the first second
            // instead of sitting at nothing until the last stream begins.
            val partLengths = parts.associate { part ->
                part.name to remoteLength(part.url)
            }.toMutableMap()
            val partDownloaded = parts.associate { it.name to 0L }.toMutableMap()
            var downloaded = 0L

            /**
             * The whole video as far as it is currently known.
             *
             * A part that arrives without announcing a length is counted by what has
             * landed from it, so a download nobody could measure still shows movement
             * rather than a bar frozen at zero.
             */
            fun totalBytes(): Long {
                val declared = partLengths.values.sum()
                val undeclared = parts
                    .filter { (partLengths[it.name] ?: 0L) <= 0L }
                    .sumOf { partDownloaded[it.name] ?: 0L }
                val total = declared + undeclared
                return if (total > 0L) total else downloaded
            }
            val written = mutableListOf<String>()

            for (part in parts) {
                val temporary = File(dir, "${part.name}.part")
                val httpRequest = Request.Builder().url(part.url).build()
                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty body")
                    val contentLength = body.contentLength().coerceAtLeast(0L)
                    // The response knows its own length, so the up-front estimate is
                    // replaced by the real one the moment there is one.
                    if (contentLength > 0L) partLengths[part.name] = contentLength
                    body.byteStream().use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                partDownloaded[part.name] = (partDownloaded[part.name] ?: 0L) + read
                                val total = totalBytes()
                                store.updateProgress(videoId, downloaded, total)
                                notifyProgress(videoId, downloaded, total)
                            }
                        }
                    }
                }
                // Renamed out of the temporary name only once it is whole, so a file that
                // is still arriving can never be mistaken for one that is finished.
                val final = File(dir, part.name)
                if (!temporary.renameTo(final)) {
                    temporary.delete()
                    error("Could not finalise ${part.name}")
                }
                written += final.name
            }

            val entry = VideoDownloadStore.Entry(
                videoId = videoId,
                title = request.title,
                channelName = request.channelName,
                thumbnailUrl = request.thumbnailUrl,
                videoFile = written.singleOrNull(),
                videoPartFile = written.getOrNull(0).takeIf { written.size > 1 },
                audioPartFile = written.getOrNull(1),
                videoMimeType = when (source) {
                    is AuraVideo.VideoStreamSource.Single -> source.mimeType
                    is AuraVideo.VideoStreamSource.Merged -> source.videoMimeType
                },
                audioMimeType = (source as? AuraVideo.VideoStreamSource.Merged)?.audioMimeType,
                height = (source as? AuraVideo.VideoStreamSource.Merged)?.height ?: 0,
                quality = request.quality,
                codec = request.codec,
                sizeBytes = written.sumOf { File(dir, it).length() },
                durationMs = request.durationMs,
                downloadedAt = System.currentTimeMillis(),
            )
            store.complete(entry)
            notifyDone(request)
        } catch (e: Exception) {
            // Nothing half-written is kept: the store only ever holds complete files.
            partsFor(videoId).forEach { File(dir, "$it.part").delete() }
            Timber.tag(TAG).w(e, "Download failed for %s", videoId)
            store.markFailed(videoId)
            notifyFailed(request, e)
        }
    }

    private fun partsFor(videoId: String) = listOf("$videoId.mp4", "$videoId.v", "$videoId.a")

    private class Part(val name: String, val url: String)

    /**
     * How long the remote says the file at [url] is, or 0 when it will not say.
     *
     * Asked with a single ranged byte where the CDN allows it, because that is the
     * cheapest way to be told a length without starting the transfer; a plain header
     * request is tried first and a refused one falls back to that.
     */
    private fun remoteLength(url: String): Long {
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                } else {
                    null
                }
            }
        }.getOrNull()?.takeIf { it > 0L }?.let { return it }

        return runCatching {
            client.newCall(
                Request.Builder().url(url).header("Range", "bytes=0-0").build(),
            ).execute().use { response ->
                // A ranged answer carries the whole length in Content-Range rather than
                // in Content-Length, which would only ever be the one byte asked for.
                response.header("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
            }
        }.getOrNull() ?: 0L
    }

    // ------------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManagerCompat::class.java) ?: return
        manager.createNotificationChannel(
            androidx.core.app.NotificationChannelCompat.Builder(
                CHANNEL_ID,
                androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW,
            )
                .setName(getString(R.string.downloading))
                .setDescription(getString(R.string.video_downloads))
                .setShowBadge(false)
                .build(),
        )
    }

    /** The ongoing notification for the fetch in hand. */
    private fun notifyProgress(videoId: String, downloaded: Long, total: Long) {
        // Progress is announced a few times a second at most. More often than that and
        // the system spends its budget re-rendering a bar nobody can read that fast,
        // which is how a transfer ends up looking like it has stalled.
        val percent = if (total > 0) ((downloaded.toDouble() / total) * 100).toInt() else 0
        if (total > 0 && percent == lastNotifiedPercent && downloaded - lastNotifiedBytes < NOTIFY_STEP_BYTES) {
            return
        }
        lastNotifiedPercent = percent
        lastNotifiedBytes = downloaded
        promoteForeground(videoId, downloaded, total)
    }

    private fun promoteForeground(videoId: String, downloaded: Long, total: Long) {
        val manager = getSystemService(NotificationManagerCompat::class.java) ?: return
        val request = store.request(videoId)
        val percent = if (total > 0) ((downloaded.toDouble() / total) * 100).toInt() else 0
        val text = when {
            total > 0 -> buildString {
                append("$percent% • ${formatBytes(downloaded)} / ${formatBytes(total)}")
                // What is still to come, which is the question a percentage cannot
                // answer on its own.
                val left = (total - downloaded).coerceAtLeast(0L)
                if (left > 0L) {
                    append(" • ")
                    append(getString(R.string.video_download_left, formatBytes(left)))
                }
            }

            else -> getString(R.string.video_downloading)
        }
        val builder = (request?.let { baseBuilder(it, text) }
            ?: NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.video_downloads))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(appIntent(videoId)))
            .setProgress(100, percent, total <= 0)
            .build()

        // Promoted rather than merely posted: without it the transfer is the app's own
        // background work, and the system is entitled to end it whenever it likes. The
        // notification is never cancelled on the way - cancelling it here is what used
        // to make a download show nothing at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_ID,
                builder,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_ID, builder)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            manager.notify(FOREGROUND_ID, builder)
        }
    }

    /**
     * Keeps the ongoing notification describing what is still moving.
     *
     * The foreground notification is a single thing, so when more than one video is on
     * its way it follows the work rather than the last write to it.
     */
    private fun updateForeground() {
        val remaining = jobs.keys.firstOrNull() ?: return
        val progress = store.progressFor(remaining)
        promoteForeground(remaining, progress?.downloadedBytes ?: 0L, progress?.totalBytes ?: 0L)
    }

    private fun notifyDone(request: VideoDownloadStore.Request) {
        val manager = getSystemService(NotificationManagerCompat::class.java) ?: return
        lastNotifiedPercent = -1
        manager.notify(
            notificationId(request.videoId),
            baseBuilder(request, getString(R.string.video_download_complete))
                .setProgress(0, 0, false)
                .setOngoing(false)
                .build(),
        )
    }

    private fun notifyFailed(request: VideoDownloadStore.Request, error: Exception) {
        val manager = getSystemService(NotificationManagerCompat::class.java) ?: return
        lastNotifiedPercent = -1
        manager.notify(
            notificationId(request.videoId),
            baseBuilder(request, error.message ?: getString(R.string.video_download_failed))
                .setProgress(0, 0, false)
                .setOngoing(false)
                .build(),
        )
    }

    private fun baseBuilder(request: VideoDownloadStore.Request, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(request.title)
            .setContentText(request.channelName.ifBlank { text })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(appIntent(request.videoId))
            .addAction(
                R.drawable.close,
                getString(android.R.string.cancel),
                serviceIntent(ACTION_CANCEL, request.videoId),
            )

    private fun appIntent(videoId: String): PendingIntent = PendingIntent.getActivity(
        this,
        videoId.hashCode(),
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_VIDEO_ID, videoId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, videoId: String): PendingIntent =
        PendingIntent.getService(
            this,
            (action + videoId).hashCode(),
            Intent(this, VideoDownloadService::class.java)
                .setAction(action)
                .putExtra(EXTRA_VIDEO_ID, videoId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun notificationId(videoId: String): Int = videoId.hashCode()

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    companion object {
        private const val TAG = "VideoDownloads"

        const val CHANNEL_ID = "video_downloads"
        private const val FOREGROUND_ID = 2

        /** Progress worth re-rendering the notification for: a whole percent, or a chunk. */
        private const val NOTIFY_STEP_BYTES = 512 * 1024L

        const val ACTION_DOWNLOAD = "com.auramusic.app.video.DOWNLOAD"
        const val ACTION_CANCEL = "com.auramusic.app.video.CANCEL"
        const val ACTION_CANCEL_ALL = "com.auramusic.app.video.CANCEL_ALL"
        const val EXTRA_VIDEO_ID = "video_id"

        /** Asks for a video. The record of what was asked for is written before this. */
        fun start(context: Context, videoId: String) {
            val intent = Intent(context, VideoDownloadService::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_VIDEO_ID, videoId)
            // The foreground flavour, because this service goes to the foreground the
            // moment it starts. Started as an ordinary background service it is refused
            // from the background - and where it does start, it is killed for reaching
            // the foreground without having been asked to.
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, videoId: String) {
            val intent = Intent(context, VideoDownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_VIDEO_ID, videoId)
            // Deliberately not the foreground flavour: a cancel has nothing to show,
            // and a service started that way has to promote a notification it would
            // only have to take straight back down. A cancel arrives while the listener
            // is in the app, or as an action on the notification of a service that is
            // already running.
            runCatching { context.startService(intent) }
        }
    }
}
