package com.auramusic.app.utils

import android.content.Context
import com.auramusic.app.constants.VideoCodec
import com.auramusic.app.constants.VideoQuality
import com.auramusic.auravideo.AuraVideo
import com.auramusic.auravideo.AuraVideo.VideoStreamResult
import com.auramusic.auravideo.AuraVideo.VideoSearchResult
import com.auramusic.auravideo.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object AuraPlayerUtils {
    private const val logTag = "AuraPlayerUtils"

    /**
     * Application context, set once at start-up so downloads can be consulted without
     * threading a context through every playback entry point.
     */
    @Volatile
    var currentContextForDownloads: Context? = null

    /**
     * Set the preferred video quality before fetching video URLs
     */
    /**
     * The quality playback is currently asking for, so a caller that changes it
     * temporarily can put it back.
     */
    var preferredVideoQuality: VideoQuality = VideoQuality.QUALITY_720P
        private set

    /**
     * The codec playback is currently asking for, kept alongside the quality so a caller
     * that changes one temporarily can put both back.
     */
    var preferredVideoCodec: VideoCodec = VideoCodec.CODEC_AUTO
        private set

    fun setPreferredVideoQuality(quality: VideoQuality) {
        preferredVideoQuality = quality
        AuraVideo.setPreferredVideoQuality(quality.toLibrary())
    }

    fun setPreferredVideoCodec(codec: VideoCodec) {
        preferredVideoCodec = codec
        AuraVideo.setPreferredCodec(codec.toLibrary())
    }

    /** Applies both preferences at once, so a caller never leaves one of them behind. */
    fun setPreferredVideoFormat(quality: VideoQuality, codec: VideoCodec) {
        setPreferredVideoQuality(quality)
        setPreferredVideoCodec(codec)
    }

    suspend fun getVideoStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        Timber.tag(logTag).d("AuraPlayerUtils: Fetching video stream URL for videoId: $videoId")

        AuraVideo.getVideoStreamUrl(videoId).map { result ->
            Timber.tag(logTag).d("AuraPlayerUtils: Successfully obtained video URL with mimeType: ${result.mimeType}")
            "${result.url}|${result.mimeType}"
        }.also { result ->
            result.onSuccess { _ ->
                Timber.tag(logTag).d("AuraPlayerUtils: Successfully obtained video URL")
            }.onFailure { e ->
                Timber.tag(logTag).e(e, "AuraPlayerUtils: Failed to get video URL")
            }
        }
    }

    /**
     * Resolve a video stream as either a single muxed URL or a pair of
     * video-only + audio-only streams that the player must merge.
     *
     * For quality > 720p, a single muxed URL is physically not available from
     * YouTube — the caller MUST handle the [AuraVideo.VideoStreamSource.Merged]
     * case to actually display 1080p video.
     */
    suspend fun getVideoStreamSource(
        videoId: String,
        quality: VideoQuality = preferredVideoQuality,
        codec: VideoCodec = preferredVideoCodec,
    ): Result<AuraVideo.VideoStreamSource> =
        withContext(Dispatchers.IO) {
            // A downloaded video is played from disk. Checked first, so this holds for
            // every route into playback - opening it, resuming it, autoplay - and costs
            // nothing on a video that was never downloaded.
            currentContextForDownloads?.let { ctx ->
                com.auramusic.app.video.VideoDownloadStore.load(ctx)
                com.auramusic.app.video.VideoDownloadStore.localSource(ctx, videoId)?.let { local ->
                    Timber.tag(logTag).d("AuraPlayerUtils: Playing downloaded video $videoId from disk")
                    return@withContext Result.success(local)
                }
            }
            Timber.tag(logTag).d(
                "AuraPlayerUtils: Resolving stream source for videoId: $videoId " +
                    "(${quality.label}, ${codec.label})"
            )
            AuraVideo.getVideoStreamSource(videoId, quality.toLibrary(), codec.toLibrary()).also { result ->
                result.onSuccess { source ->
                    when (source) {
                        is AuraVideo.VideoStreamSource.Single ->
                            Timber.tag(logTag).d("AuraPlayerUtils: Single source (${source.mimeType})")
                        is AuraVideo.VideoStreamSource.Merged ->
                            Timber.tag(logTag)
                                .d("AuraPlayerUtils: Merged source ${source.height}p video + audio")
                    }
                }.onFailure { e ->
                    Timber.tag(logTag).e(e, "AuraPlayerUtils: Failed to resolve stream source")
                }
            }
        }

    /**
     * Get video stream URL with automatic search fallback for regular songs
     * This searches for official music video if direct lookup fails
     */
    suspend fun getVideoStreamUrlWithFallback(songTitle: String, artistName: String, videoId: String, isVideoSong: Boolean = true): Result<VideoSearchResult> = withContext(Dispatchers.IO) {
        Timber.tag(logTag).d("AuraPlayerUtils: Trying to get video with fallback for: $songTitle by $artistName (isVideoSong=$isVideoSong)")

        try {
            val result = AuraVideo.getVideoStreamUrlWithFallback(songTitle, artistName, videoId, isVideoSong)
            result.onSuccess { searchResult ->
                Timber.tag(logTag).d("AuraPlayerUtils: Found video via fallback: ${searchResult.videoId}")
            }
            result
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "AuraPlayerUtils: Fallback search also failed")
            Result.failure(e)
        }
    }

    suspend fun hasVideoPlayback(videoId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            AuraVideo.hasVideoPlayback(videoId)
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "AuraPlayerUtils: Error checking video availability")
            false
        }
    }

    suspend fun getVideoDetails(videoId: String): Result<VideoItem> = withContext(Dispatchers.IO) {
        AuraVideo.getVideoDetails(videoId)
    }
}

/**
 * Maps app-level video preferences to the library enums. Both sides carry the same
 * names and the same ladder, so this is a checked identity rather than a table that
 * has to be kept in step by hand.
 */
fun appVideoQualityToLibrary(quality: VideoQuality): AuraVideo.VideoQuality =
    AuraVideo.VideoQuality.entries.firstOrNull { it.name == quality.name }
        ?: AuraVideo.VideoQuality.QUALITY_720P

fun VideoQuality.toLibrary(): AuraVideo.VideoQuality = appVideoQualityToLibrary(this)

fun VideoCodec.toLibrary(): AuraVideo.VideoCodec =
    AuraVideo.VideoCodec.entries.firstOrNull { it.name == name }
        ?: AuraVideo.VideoCodec.CODEC_AUTO
