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
            // plays silence and half a video plays a frozen frame.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enqueue(videoId: String) {
        if (jobs.containsKey(videoId)) return
        val job = scope.launch { runDownload(videoId) }
        jobs[videoId] = job
        job.invokeOnCompletion {
            scope.launch {
                jobs.remove(videoId)
                if (jobs.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
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

            // Sizes are learned as the responses arrive, so the total starts at zero and
            // the notification shows an unknown length until then - which is honest, and
            // better than a bar that fills towards a number that was never real.
            var downloaded = 0L
            var total = 0L
            val written = mutableListOf<String>()

            for (part in parts) {
                val temporary = File(dir, "${part.name}.part")
                val httpRequest = Request.Builder().url(part.url).build()
                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty body")
                    val contentLength = body.contentLength().coerceAtLeast(0L)
                    body.byteStream().use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                store.updateProgress(videoId, downloaded, total)
                                notifyProgress(request, downloaded, total)
                            }
                        }
                    }
                    total += contentLength
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
    private fun notifyProgress(request: VideoDownloadStore.Request, downloaded: Long, total: Long) {
        val manager = getSystemService(NotificationManagerCompat::class.java) ?: return
        val percent = if (total > 0) ((downloaded.toDouble() / total) * 100).toInt() else 0
        val text = if (total > 0) {
            "${percent}% • ${formatBytes(downloaded)} / ${formatBytes(total)}"
        } else {
            getString(R.string.video_downloading)
        }
        val notification = baseBuilder(request, text)
            .setProgress(100, percent, total <= 0)
            .build()

        // Promoted rather than merely posted: without it the transfer is the app's own
        // background work, and the system is entitled to end it whenever it likes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
        manager.cancel(FOREGROUND_ID)
    }

    private fun notifyDone(request: VideoDownloadStore.Request) {
        val manager = getSystemService(NotificationManagerCompat::class.java) ?: return
        manager.notify(
            notificationId(request.videoId),
            baseBuilder(request, getString(R.string.video_download_complete))
                .setProgress(0, 0, false)
                .build(),
        )
    }

    private fun notifyFailed(request: VideoDownloadStore.Request, error: Exception) {
        val manager = getSystemService(NotificationManagerCompat::class.java) ?: return
        manager.notify(
            notificationId(request.videoId),
            baseBuilder(request, error.message ?: getString(R.string.video_download_failed))
                .setProgress(0, 0, false)
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

        const val ACTION_DOWNLOAD = "com.auramusic.app.video.DOWNLOAD"
        const val ACTION_CANCEL = "com.auramusic.app.video.CANCEL"
        const val ACTION_CANCEL_ALL = "com.auramusic.app.video.CANCEL_ALL"
        const val EXTRA_VIDEO_ID = "video_id"

        /** Asks for a video. The record of what was asked for is written before this. */
        fun start(context: Context, videoId: String) {
            val intent = Intent(context, VideoDownloadService::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_VIDEO_ID, videoId)
            context.startService(intent)
        }

        fun cancel(context: Context, videoId: String) {
            val intent = Intent(context, VideoDownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_VIDEO_ID, videoId)
            context.startService(intent)
        }
    }
}
