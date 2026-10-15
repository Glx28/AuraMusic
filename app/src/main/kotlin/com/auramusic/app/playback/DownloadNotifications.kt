/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.auramusic.app.R

/**
 * Whether a download can announce itself at all, and on which channel.
 *
 * A foreground service whose notification the system will not accept is killed, and
 * the refusal comes back from outside the process - so it cannot be caught, only
 * avoided. Everything that makes a notification unacceptable is knowable beforehand:
 * notifications turned off for the app, a channel that has been blocked, a channel
 * that was never created. Asking here turns all three into an ordinary refusal rather
 * than a crash report.
 */
object DownloadNotifications {

    /** The channel songs are announced on. */
    const val SONG_CHANNEL_ID = ExoDownloadService.CHANNEL_ID

    /**
     * True when a notification can be posted on [channelId] right now.
     *
     * A channel asked about before it exists reads as missing, which is the same
     * answer - so callers that want the channel made first call [createIfMissing].
     */
    fun canPost(context: Context, channelId: String = SONG_CHANNEL_ID): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = runCatching { manager.getNotificationChannel(channelId) }.getOrNull()
            ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Creates the channel if it is not already there, at an importance that shows.
     *
     * Never touches one that exists. A channel the listener has blocked stays blocked,
     * and quietly resetting it would undo something they did on purpose.
     */
    fun createIfMissing(context: Context, channelId: String = SONG_CHANNEL_ID) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = NotificationManagerCompat.from(context)
        if (runCatching { manager.getNotificationChannel(channelId) }.getOrNull() != null) return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    context.getString(R.string.downloading),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
    }
}