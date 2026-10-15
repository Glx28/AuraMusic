/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.video

import android.app.Notification
import android.app.NotificationManager
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
import com.auramusic.app.constants.VideoCodec
import com.auramusic.app.constants.VideoQuality
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

    private val client: OkHttpClient by lazy { httpClient(VideoBlockDownloader.DEFAULT_THREADS) }

    /** The channel this service was told to announce itself on, decided by the caller. */
    private var announcedChannel: String = CHANNEL_ID

    /**
     * A client with a connection pool to match how much is being fetched at once.
     *
     * A pool sized for one connection is what a multi-block transfer ends up waiting
     * on: the blocks are ready to go and there is nowhere for them to go.
     */
    private fun httpClient(threads: Int): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(com.auramusic.innertube.YouTube.proxy)
            .proxyAuthenticator { _, response ->
                com.auramusic.innertube.YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .apply {
                runCatching {
                    connectionPool(
                        okhttp3.ConnectionPool(
                            maxIdleConnections = (threads * 2).coerceAtLeast(4),
                            keepAliveDuration = 5,
                            java.util.concurrent.TimeUnit.MINUTES,
                        ),
                    )
                    retryOnConnectionFailure(true)
                }
            }
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Made before anything can ask for a notification to be posted on it, and
        // never waited on: the announcement that follows needs this to exist and
        // nothing else.
        createVideoDownloadChannel(CHANNEL_ID)
        // The service outlives the screen that asked for a download, so the record of
        // what was asked for is read here rather than being carried in the intent: a
        // restarted process has the intent but not the store in memory.
        scope.launch { store.load() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val videoId = intent?.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        // The channel was chosen - and proved to be one that can be posted on - before
        // the service was started, so it is taken from the intent rather than worked
        // out here. Asking the system anything on this path costs time the deadline
        // does not give back.
        announcedChannel = intent?.getStringExtra(EXTRA_CHANNEL)?.takeIf { it.isNotBlank() }
            ?: CHANNEL_ID

        // A service started in the foreground has to be in the foreground within a few
        // seconds - and "a few" is measured from startForegroundService(), not from here.
        //
        // So the first thing this does is post a notification built from nothing but
        // the channel made in onCreate and the details the intent carried. No store is
        // read, no dispatcher is waited on, no channel is looked up: every one of those
        // is a way to spend a budget that does not grow, and a service that spends it
        // is a process the system kills, taking the download with it. Whether the
        // download is even wanted is decided afterwards, not before.
        val requiresForeground =
            intent?.getBooleanExtra(EXTRA_REQUIRES_FOREGROUND, false) == true
        val requested = if (action == ACTION_DOWNLOAD) requestFrom(intent) else null
        if (action == ACTION_DOWNLOAD) {
            val announced = announceImmediately(requested, videoId)
            when {
                !announced && requiresForeground -> {
                    // Promised the system a notification and could not keep that
                    // promise. Standing down at once is the only way not to be killed
                    // for it; a transfer that was never announced is not worth a
                    // crash, and it is still in the list to be tried again.
                    failUnstartable(videoId)
                    return START_NOT_STICKY
                }
                // Announced, but nothing to fetch: an id that never arrived. Rather
                // than sit in the foreground claiming a transfer that is not happening.
                !announced || videoId.isBlank() -> {
                    runCatching { stopForegroundCompat() }
                    failUnstartable(videoId)
                    return START_NOT_STICKY
                }
            }
        }

        when (action) {
            ACTION_DOWNLOAD -> if (videoId.isNotBlank()) enqueue(videoId, requested)
            ACTION_CANCEL -> if (videoId.isNotBlank()) cancel(videoId)
            ACTION_CANCEL_ALL -> jobs.keys.toList().forEach { cancel(it) }
            // Nothing to resume into: a restart with no job would have nothing to fetch,
            // and a half-written file is discarded rather than resumed - half a video
            // plays silence and half a video plays a frozen frame.
            else -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // A cancel that starts a service with nothing to fetch, or a download refused
        // for want of a record of it, must not leave a service behind with no work to
        // do and a notification claiming there is some.
        if (jobs.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enqueue(videoId: String, request: VideoDownloadStore.Request?) {
        // Already being fetched: the notification for it was raised a moment ago, on
        // the way in, and the fetch already in flight is the one that finishes it.
        if (jobs.containsKey(videoId)) return
        val job = scope.launch { runDownload(videoId, request) }
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

    private suspend fun runDownload(videoId: String, requested: VideoDownloadStore.Request?) {
        // What the download is told about itself comes from the request that asked for
        // it, which arrived with the intent. The stored record is only consulted for a
        // download this process did not start - a retry, say - so the ordinary path
        // waits on nothing at all before the first byte moves.
        val request = requested ?: run {
            // A restarted process has the download on disk rather than in memory, and
            // a download that cannot find itself has nothing to fetch.
            store.load()
            store.request(videoId)
        }
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
            // there is nothing for a percentage to be a percentage of. A stream URL
            // usually states its own length, so that is read first and a request is
            // only made to ask when it does not.
            val downloader = VideoBlockDownloader { threads -> httpClient(threads) }
            val partLengths = parts.associate { part ->
                part.name to downloader.lengthOf(part.url, VideoBlockDownloader.stripRangeParam(part.url), client)
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
                // Taken in blocks, several at a time. One connection answers a video
                // exactly as fast as one request can be made however fast the link is,
                // and a block that fails is fetched again on its own rather than
                // failing the whole transfer with everything already on disk.
                downloader.download(
                    url = part.url,
                    target = temporary,
                    totalBytes = partLengths[part.name] ?: 0L,
                    threads = DOWNLOAD_THREADS,
                    onTotalChanged = { real ->
                        // The remote has told us the real length; a bar drawn against
                        // the wrong one is worse than one that settles a moment later.
                        partLengths[part.name] = real
                    },
                    onBytes = { writtenForPart, partTotal ->
                        partDownloaded[part.name] = writtenForPart
                        if (partTotal > 0L) partLengths[part.name] = partTotal
                        downloaded = partDownloaded.values.sum()
                        val total = totalBytes()
                        store.updateProgress(videoId, downloaded, total)
                        notifyProgress(request, videoId, downloaded, total)
                    },
                )
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
        createVideoDownloadChannel(CHANNEL_ID)
    }

    private fun createVideoDownloadChannel(id: String) {
        val manager = getSystemService(NotificationManagerCompat::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                androidx.core.app.NotificationChannelCompat.Builder(
                    id,
                    androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW,
                )
                    .setName(getString(R.string.downloading))
                    .setDescription(getString(R.string.video_downloads))
                    .setShowBadge(false)
                    .build(),
            )
        }.onFailure {
            Timber.tag(TAG).w(it, "Channel %s could not be created", id)
        }
    }

    /**
     * The channel this download can actually be announced on, or null when none will do.
     *
     * A foreground service that cannot post its notification is not quiet about it: the
     * system kills the process, taking the transfer with it. Two things stop a
     * notification being posted - a channel that was never created, and a channel that
     * exists but has been turned off - and the second cannot be undone by creating the
     * channel again, because a channel the listener has blocked stays blocked. So a
     * blocked channel is retried under a second name, and when even that is unusable the
     * caller is told so here rather than finding out by crashing.
     */
    private fun usableChannelId(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CHANNEL_ID
        val manager = NotificationManagerCompat.from(this)
        // Notifications off for the app is the same problem as a blocked channel and no
        // channel of ours can answer it.
        if (!manager.areNotificationsEnabled()) return null
        listOf(CHANNEL_ID, FALLBACK_CHANNEL_ID).forEach { id ->
            createVideoDownloadChannel(id)
            val channel = runCatching { manager.getNotificationChannel(id) }.getOrNull()
            if (channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE) {
                return id
            }
            Timber.tag(TAG).i("Channel %s cannot carry a notification", id)
        }
        return null
    }

    /** The ongoing notification for the fetch in hand. */
    private fun notifyProgress(request: VideoDownloadStore.Request, videoId: String, downloaded: Long, total: Long) {
        // Progress is announced a few times a second at most. More often than that and
        // the system spends its budget re-rendering a bar nobody can read that fast,
        // which is how a transfer ends up looking like it has stalled.
        val percent = if (total > 0) ((downloaded.toDouble() / total) * 100).toInt() else 0
        if (total > 0 && percent == lastNotifiedPercent && downloaded - lastNotifiedBytes < NOTIFY_STEP_BYTES) {
            return
        }
        lastNotifiedPercent = percent
        lastNotifiedBytes = downloaded
        promoteForeground(request, videoId, downloaded, total)
    }

    /**
     * Puts the service in the foreground before doing anything else.
     *
     * Deliberately the cheapest thing this service can do: it builds one notification
     * from the channel already created and the details already carried, and hands it
     * straight over. It asks the system nothing - no channel to be looked up, no
     * notifications to be checked, nothing read - because every one of those is time
     * taken out of a deadline that is already running, and a service that misses it is
     * killed rather than reported.
     *
     * True when the service is now in the foreground. False means it promised the
     * system a notification and could not keep that promise, which the caller has to
     * act on rather than shrug at.
     */
    private fun announceImmediately(
        request: VideoDownloadStore.Request?,
        videoId: String,
    ): Boolean {
        val notification = NotificationCompat.Builder(this, announcedChannel)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(
                request?.title?.takeIf { it.isNotBlank() }
                    ?: videoId.takeIf { it.isNotBlank() }
                    ?: getString(R.string.video_downloads),
            )
            .setContentText(getString(R.string.video_downloading))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.video_downloading)))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            // Nothing is known yet, which is said plainly rather than filled in with
            // a zero that would read as "none downloaded, none to come".
            .setProgress(0, 0, true)
            .setContentIntent(appIntent(videoId))
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundSafely(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForegroundSafely(FOREGROUND_ID, notification)
        }
    }

    /**
     * Repoints the ongoing notification at where the transfer has got to.
     *
     * Only ever called after the service is already in the foreground, so unlike the
     * announcement above this one can afford to ask the system which channel it can
     * actually post on - a channel the listener has turned off cannot be posted to,
     * and one that is left blocked is a notification that stops appearing mid-transfer.
     */
    private fun promoteForeground(
        request: VideoDownloadStore.Request?,
        videoId: String,
        downloaded: Long,
        total: Long,
    ): Boolean {
        val channelId = usableChannelId()
        if (channelId == null) {
            // Nothing can carry the notification, so this cannot be a foreground
            // service: the system kills the process for one that cannot post. The
            // download is left to run as an ordinary service instead of being taken
            // down with it.
            Timber.tag(TAG).w("No usable notification channel - the download cannot be announced")
            return false
        }
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
        // The transfer is happening either way, so a request that did not arrive intact
        // still gets a notification: an unnamed one is far better than none, and a
        // service that promised one and stayed silent is a process the system kills.
        val title = request?.title?.takeIf { it.isNotBlank() }
            ?: videoId.takeIf { it.isNotBlank() }
            ?: getString(R.string.video_downloads)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(title)
            .setContentText(
                request?.channelName?.takeIf { it.isNotBlank() } ?: text,
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, total <= 0)
            .setContentIntent(appIntent(videoId))
            .apply {
                if (videoId.isNotBlank()) {
                    addAction(
                        R.drawable.close,
                        getString(android.R.string.cancel),
                        serviceIntent(ACTION_CANCEL, videoId),
                    )
                }
            }
            .build()

        // Promoted rather than merely posted: without it the transfer is the app's own
        // background work, and the system is entitled to end it whenever it likes. The
        // notification is never cancelled on the way - cancelling it here is what used
        // to make a download show nothing at all. Nothing above may return early: a
        // service that has promised a notification and not delivered it is killed, and
        // the download goes with it.
        // Before O there is no such thing as a foreground notification: the transfer has to
        // be posted by hand for anything to appear. It is posted, never cancelled.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            NotificationManagerCompat.from(this).notify(FOREGROUND_ID, notification)
            return true
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundSafely(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForegroundSafely(FOREGROUND_ID, notification)
        }
    }

    /**
     * Enters the foreground, or gets out of the way.
     *
     * A notification the system still refuses throws here rather than killing us - the
     * daily limit on background data work, a start the system decided was not allowed,
     * a channel that has been turned off since it was checked. None of those are worth
     * a crash report: the download is marked failed so it can be tried again, and the
     * service stands down rather than sitting in the foreground announcing nothing.
     */
    private fun startForegroundSafely(id: Int, notification: Notification, type: Int): Boolean {
        return try {
            startForeground(id, notification, type)
            true
        } catch (e: RuntimeException) {
            // The system can still say no: the daily limit on background data work, a
            // start it decided was not allowed, a channel turned off since we last
            // looked. None of those are worth a crash report.
            Timber.tag(TAG).e(e, "Refused the foreground; the download will not be announced")
            runCatching { stopForegroundCompat() }
            false
        }
    }

    private fun startForegroundSafely(id: Int, notification: Notification): Boolean {
        return try {
            startForeground(id, notification)
            true
        } catch (e: RuntimeException) {
            Timber.tag(TAG).e(e, "Refused the foreground; the download will not be announced")
            runCatching { stopForegroundCompat() }
            false
        }
    }

    /**
     * Fails a transfer the service could not start.
     *
     * Marked failed rather than left pending: a download that is on the list but is
     * not moving is indistinguishable from one that has hung, and the only way back
     * from a hang is to clear it and ask again.
     */
    private fun failUnstartable(videoId: String) {
        if (videoId.isNotBlank()) store.markFailed(videoId)
        stopSelf()
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
        promoteForeground(
            request = store.request(remaining),
            videoId = remaining,
            downloaded = progress?.downloadedBytes ?: 0L,
            total = progress?.totalBytes ?: 0L,
        )
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
        NotificationCompat.Builder(this, usableChannelId() ?: CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
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

        /**
         * A second name for the same channel, used when the first has been turned off.
         *
         * A channel the listener has blocked stays blocked - creating it again does not
         * undo that - and a foreground service whose notification cannot be posted is
         * not left running, it is killed. So the announcement moves to a channel they
         * have not seen rather than being given up on.
         */
        private const val FALLBACK_CHANNEL_ID = "video_downloads_alt"
        private const val FOREGROUND_ID = 2

        /** Progress worth re-rendering the notification for: a whole percent, or a chunk. */
        private const val NOTIFY_STEP_BYTES = 512 * 1024L

        /**
         * How many parts of a file are fetched at once.
         *
         * Three is enough to fill a normal connection without crowding it: past that
         * the requests spend longer queueing than transferring, and a connection that
         * is already busy has nothing to give the extra ones.
         */
        private const val DOWNLOAD_THREADS = VideoBlockDownloader.DEFAULT_THREADS

        const val ACTION_DOWNLOAD = "com.auramusic.app.video.DOWNLOAD"
        const val ACTION_CANCEL = "com.auramusic.app.video.CANCEL"
        const val ACTION_CANCEL_ALL = "com.auramusic.app.video.CANCEL_ALL"
        const val EXTRA_VIDEO_ID = "video_id"

        /**
         * Whether this service was started expecting to reach the foreground.
         *
         * Carried in the intent rather than assumed, because the two ways in are
         * opposites: a start that promised the system a notification has to keep that
         * promise or stand down, and one that could not make the promise is free to
         * fetch unannounced rather than be killed for silence.
         */
        const val EXTRA_REQUIRES_FOREGROUND = "requires_foreground"

        // What the download is, carried with the intent so that naming it never
        // costs the service any of the seconds it has to announce itself in.
        const val EXTRA_TITLE = "title"

        /** The channel to announce on, proved postable by whoever started the service. */
        const val EXTRA_CHANNEL = "channel"

        /** The name of the channel the video came from, which is text, not a channel id. */
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_THUMBNAIL = "thumbnail"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_DURATION = "duration"

        /**
     * Asks for a video. The record of what was asked for is written before this.
     *
     * Everything the notification needs travels with the intent rather than being
     * fetched afterwards. The service has a few seconds from this call to put a
     * notification up, and every step between here and there is time spent out of a
     * budget that does not grow. The channel it will post on is settled here too, for
     * the same reason - and because asking the system whether a channel works is slow
     * enough to be worth doing before the clock rather than after it.
     */
        fun start(context: Context, request: VideoDownloadStore.Request) {
            val intent = Intent(context, VideoDownloadService::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_VIDEO_ID, request.videoId)
                .putExtra(EXTRA_TITLE, request.title)
                .putExtra(EXTRA_CHANNEL_NAME, request.channelName)
                .putExtra(EXTRA_THUMBNAIL, request.thumbnailUrl)
                .putExtra(EXTRA_QUALITY, request.quality.name)
                .putExtra(EXTRA_CODEC, request.codec.name)
                .putExtra(EXTRA_DURATION, request.durationMs)

            // The foreground flavour, and only ever for this: the service is expected to
            // reach the foreground the moment it is started, and the system kills one
            // that promised a notification and did not deliver it. Cancels go by
            // startService instead - they have nothing to announce, and a service
            // started that way has to show something it would only take straight down.
            //
            // The channel is settled here rather than inside the service for two
            // reasons, and the second is the one that matters: a channel the listener
            // has turned off cannot carry a foreground notification, and the refusal
            // arrives from outside the process where nothing can catch it. So there is
            // nothing to ask here unless there is somewhere to ask it, and the answer
            // travels with the rest of the details.
            //
            // When there is nowhere to post - notifications off for the app, or every
            // channel turned off - the transfer is not started in the foreground at
            // all. It still happens, in the background, for as long as the system
            // allows, and the button still counts it.
            val channel = usableChannelId(context)
            if (channel != null) {
                intent.putExtra(EXTRA_CHANNEL, channel)
                intent.putExtra(EXTRA_REQUIRES_FOREGROUND, true)
                ContextCompat.startForegroundService(context, intent)
            } else {
                runCatching { context.startService(intent) }
            }
        }

        /**
         * A channel this download can actually be announced on, or null when none will do.
         *
         * A foreground service whose notification the system will not accept is killed,
         * and nothing the app does afterwards can prevent that: the refusal comes back
         * from outside the process and takes the app down with it. The only way not to
         * be killed by it is not to ask - so the channel is chosen and proved here,
         * before the service is started, and the answer is handed over rather than
         * looked up again on the far side of a deadline.
         *
         * A channel the listener has turned off stays turned off: creating it again does
         * not undo that. So the announcement moves to a channel they have not seen.
         */
        fun usableChannelId(context: Context): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CHANNEL_ID
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return null
            listOf(CHANNEL_ID, FALLBACK_CHANNEL_ID).forEach { id ->
                createChannel(context, id)
                val channel = runCatching { manager.getNotificationChannel(id) }.getOrNull()
                if (channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE) {
                    return id
                }
            }
            return null
        }

        /** Creates [id] if it is not already there, and says so rather than failing loudly. */
        private fun createChannel(context: Context, id: String) {
            val manager = NotificationManagerCompat.from(context)
            runCatching {
                manager.createNotificationChannel(
                    androidx.core.app.NotificationChannelCompat.Builder(
                        id,
                        androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW,
                    )
                        .setName(context.getString(R.string.downloading))
                        .setDescription(context.getString(R.string.video_downloads))
                        .setShowBadge(false)
                        .build(),
                )
            }.onFailure { Timber.tag(TAG).w(it, "Channel %s could not be created", id) }
        }

        /** What the download was asked for, as it arrived with the intent. */
        private fun requestFrom(intent: Intent?): VideoDownloadStore.Request? {
            val source = intent ?: return null
            val videoId = source.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
            if (videoId.isBlank()) return null
            return VideoDownloadStore.Request(
                videoId = videoId,
                title = source.getStringExtra(EXTRA_TITLE).orEmpty(),
                channelName = source.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty(),
                thumbnailUrl = source.getStringExtra(EXTRA_THUMBNAIL),
                quality = VideoQuality.fromName(source.getStringExtra(EXTRA_QUALITY)),
                codec = VideoCodec.fromName(source.getStringExtra(EXTRA_CODEC)),
                durationMs = source.getLongExtra(EXTRA_DURATION, 0L),
            )
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
