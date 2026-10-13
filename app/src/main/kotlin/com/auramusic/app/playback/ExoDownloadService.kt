/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.auramusic.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
class ExoDownloadService : DownloadService(
    NOTIFICATION_ID,
    1000L,
    CHANNEL_ID,
    R.string.downloading,
    0
) {
    @Inject
    lateinit var downloadUtil: DownloadUtil

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == REMOVE_ALL_PENDING_DOWNLOADS) {
            downloadManager.currentDownloads.forEach { download ->
                downloadManager.removeDownload(download.request.id)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun getDownloadManager() = downloadUtil.downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification =
        Notification.Builder.recoverBuilder(
            this, downloadUtil.downloadNotificationHelper.buildProgressNotification(
                this,
                R.drawable.download,
                null,
                downloadLabel(this, downloads),
                downloads,
                notMetRequirements
            )
        ).addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.close),
                getString(android.R.string.cancel),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, ExoDownloadService::class.java).setAction(
                        REMOVE_ALL_PENDING_DOWNLOADS
                    ),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        ).build()


    /**
     * This helper will outlive the lifespan of a single instance of [ExoDownloadService]
     */
    class TerminalStateNotificationHelper(
        private val context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        private var nextNotificationId: Int,
    ) : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            if (download.state == Download.STATE_FAILED) {
                val notification = notificationHelper.buildDownloadFailedNotification(
                    context,
                    R.drawable.error,
                    null,
                    downloadLabel(context, listOf(download))
                )
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            }
        }
    }

    companion object {
        /**
         * What the notification says is happening.
         *
         * A song carries its title in the download's data. A video carries the whole record
         * it needs to be played back from, so its title and channel are read out of that
         * rather than shown as the raw text - a notification is one line, and a video's
         * record is a page. Several at once are counted rather than named, since there is
         * only room for one line and the number is the thing worth knowing.
         */
        private fun downloadLabel(context: Context, downloads: List<Download>): String? {
            if (downloads.size != 1) {
                return context.resources.getQuantityString(R.plurals.n_download, downloads.size, downloads.size)
            }
            val data = downloads[0].request.data ?: return null
            val text = Util.fromUtf8Bytes(data)
            // Songs store the title and nothing else; videos store a JSON record.
            if (!text.trimStart().startsWith("{")) return text
            return runCatching {
                val record = org.json.JSONObject(text)
                val title = record.optString("t").trim()
                val channel = record.optString("c").trim()
                when {
                    title.isEmpty() && channel.isEmpty() -> null
                    channel.isEmpty() -> title
                    title.isEmpty() -> channel
                    else -> "$title \u2022 $channel"
                }
            }.getOrNull()
        }

        const val CHANNEL_ID = "download"
        const val NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val REMOVE_ALL_PENDING_DOWNLOADS = "REMOVE_ALL_PENDING_DOWNLOADS"
    }
}
