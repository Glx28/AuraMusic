/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
 import androidx.compose.foundation.layout.fillMaxSize
 import androidx.compose.foundation.layout.fillMaxWidth
 import androidx.compose.foundation.layout.height
 import androidx.compose.foundation.layout.padding
 import androidx.compose.foundation.layout.size
 import androidx.compose.foundation.layout.width
 import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
 import androidx.compose.foundation.shape.CircleShape
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.foundation.basicMarquee
 import androidx.compose.ui.text.style.TextOverflow
 import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MenuOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
  import androidx.media3.common.C
  import androidx.media3.common.MediaItem
  import androidx.media3.common.MediaMetadata
  import androidx.media3.common.Timeline
 import coil3.compose.AsyncImage
 import com.auramusic.app.R
 import com.auramusic.app.db.entities.Song
 import com.auramusic.app.playback.PlayerConnection
 import com.auramusic.app.playback.queues.YouTubeQueue
 import com.auramusic.app.playback.queues.ListQueue
 import com.auramusic.innertube.models.WatchEndpoint
 import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
 import androidx.lifecycle.viewModelScope
 import androidx.lifecycle.compose.collectAsStateWithLifecycle
 import com.auramusic.app.utils.makeTimeString
 import com.auramusic.app.LocalPlayerConnection
 import com.auramusic.app.ui.component.LocalMenuState
 import androidx.compose.ui.platform.LocalDensity
 import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.platform.LocalWindowInfo
 import androidx.compose.ui.viewinterop.AndroidView
 import androidx.media3.ui.AspectRatioFrameLayout
 import androidx.media3.ui.PlayerView
 import com.auramusic.app.LocalListenTogetherManager
 import com.auramusic.app.constants.VideoModeEnabledKey
 import com.auramusic.app.models.toMediaMetadata
 import com.auramusic.app.ui.component.Lyrics

 // Where each fullscreen transport button sits in the row the remote walks. The slots
 // are fixed rather than positional so a button that is not drawn - the lyrics toggle
 // while a video owns the picture - does not shift the ones around it.
 private const val FULLSCREEN_PREVIOUS_SLOT = 0
 private const val FULLSCREEN_REWIND_SLOT = 1
 private const val FULLSCREEN_PLAY_SLOT = 2
 private const val FULLSCREEN_FORWARD_SLOT = 3
 private const val FULLSCREEN_NEXT_SLOT = 4
 private const val FULLSCREEN_LYRICS_SLOT = 5
 private const val FULLSCREEN_QUEUE_SLOT = 6
 private const val FULLSCREEN_EXIT_SLOT = 7
 private const val FULLSCREEN_TRANSPORT_SLOTS = 8

 @Composable
 private fun TvPlayerControlButton(
     onClick: () -> Unit,
     icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
     painter: androidx.compose.ui.graphics.painter.Painter? = null,
     contentDescription: String,
     size: androidx.compose.ui.unit.Dp = 72.dp,
     tint: Color = Color.White,
     focusRequester: FocusRequester? = null,
     modifier: Modifier = Modifier,
     onFocused: () -> Unit = {},
 ) {
     val isFocusedState = remember { mutableStateOf(false) }

     IconButton(
         onClick = onClick,
         modifier = modifier
             .size(size)
             .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
             .onFocusChanged {
                 isFocusedState.value = it.isFocused
                 // A button nobody can see is a button nobody can aim at, so landing
                 // focus on one brings the whole overlay back.
                 if (it.isFocused) onFocused()
             }
             .background(
                 color = if (isFocusedState.value) {
                     MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                 } else {
                     Color.Transparent
                 },
                 shape = CircleShape,
             )
             .border(
                 width = if (isFocusedState.value) 3.dp else 0.dp,
                 color = if (isFocusedState.value) MaterialTheme.colorScheme.primary else Color.Transparent,
                 shape = CircleShape
             ),
     ) {
         if (icon != null) {
             Icon(
                 icon,
                 contentDescription = contentDescription,
                 tint = tint,
                 modifier = Modifier.size(size * 0.6f)
             )
         } else if (painter != null) {
             Icon(
                 painter = painter,
                 contentDescription = contentDescription,
                 tint = tint,
                 modifier = Modifier.size(size * 0.6f)
             )
         }
     }
 }

  @Composable
  private fun TvQueueItem(
      window: Timeline.Window,
      isCurrentSong: Boolean,
      onClick: () -> Unit,
      modifier: Modifier = Modifier,
      focusRequester: FocusRequester? = null,
      onFocused: () -> Unit = {},
  ) {
      val isFocusedState = remember { mutableStateOf(false) }
      // Keeps the row the remote is actually on inside the panel, so pressing down
      // past the bottom of the list scrolls instead of going nowhere.
      val bringIntoViewRequester = remember { BringIntoViewRequester() }
      val bringIntoViewScope = rememberCoroutineScope()
      val borderColor = if (isFocusedState.value || isCurrentSong) {
          MaterialTheme.colorScheme.primary
      } else {
          Color.Transparent
      }
      val backgroundColor = when {
          isFocusedState.value -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
          isCurrentSong -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
          else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
      }

      val mediaItem = window.mediaItem
          val metadata = mediaItem.mediaMetadata

      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          modifier = modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .border(2.dp, borderColor, RoundedCornerShape(8.dp))
              .bringIntoViewRequester(bringIntoViewRequester)
              .onFocusChanged {
                  isFocusedState.value = it.isFocused
                  if (it.isFocused) {
                      bringIntoViewScope.launch {
                          runCatching { bringIntoViewRequester.bringIntoView() }
                      }
                      onFocused()
                  }
              }
              .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
              .focusable()
              .clickable(onClick = onClick)
              .background(backgroundColor)
              .padding(12.dp),
      ) {
          Box(
              modifier = Modifier
                  .size(56.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant),
          ) {
              AsyncImage(
                  model = metadata?.artworkUri?.toString(),
                  contentDescription = metadata?.title?.toString() ?: "Song",
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize(),
              )
          }
           Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = metadata?.title?.toString() ?: "Unknown title",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCurrentSong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(
                        iterations = 1,
                        initialDelayMillis = 3000,
                        velocity = 30.dp
                    )
                )
                Text(
                    text = metadata?.artist?.toString() ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(
                        iterations = 1,
                        initialDelayMillis = 3000,
                        velocity = 30.dp
                    )
                )
           }
          // Duration
          val durationMs = window.durationMs
          if (durationMs > 0) {
              Text(
                  text = makeTimeString(durationMs),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
          }
      }
  }

/**
 * Media3 PlayerView hosting the active player plus the switch/buffering overlays.
 * Exactly one instance may be composed at a time (split layout vs. fullscreen
 * layer): the surface claims the video output, so the two must never coexist.
 */
@Composable
private fun TvVideoView(
    activePlayer: androidx.media3.common.Player?,
    isVideoSwitching: Boolean,
    isBuffering: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = activePlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { playerView ->
                // Always sync player reference and resume surface. Keep player
                // attached across navigations to avoid black screen after 2+ re-entries.
                if (playerView.player !== activePlayer) {
                    playerView.player = activePlayer
                }
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                playerView.onResume()
                playerView.requestLayout()
            },
            onRelease = { playerView ->
                playerView.onPause()
            },
        )

        if (isVideoSwitching) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "Loading video...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        } else if (isBuffering) {
            // Lighter overlay for brief buffering mid-playback
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

/**
 * TV-compatible full-screen player with large controls optimized for remote control navigation.
 * Features large touch targets, clear visual hierarchy, and TV-friendly layout.
 * Split layout: left side = player controls, right side = queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvPlayerScreen(
    playerConnection: PlayerConnection?,
    onBackClick: () -> Unit,
    isFullScreen: Boolean,
    onFullScreenChange: (Boolean) -> Unit,
) {
    // The queue opened over the media is the first thing Back closes: leaving it
    // open and dropping straight out of fullscreen throws away the way out.
    var fullscreenQueueOpen by remember { mutableStateOf(false) }
    BackHandler {
        when {
            fullscreenQueueOpen -> fullscreenQueueOpen = false
            isFullScreen -> onFullScreenChange(false)
            else -> onBackClick()
        }
    }
    // Leaving fullscreen takes the panel with it, so it cannot reappear over the
    // split layout with no way to close it.
    LaunchedEffect(isFullScreen) {
        if (!isFullScreen) fullscreenQueueOpen = false
    }
    var duration by remember { mutableStateOf(0L) }
    var currentPosition by remember { mutableStateOf(0L) }
    var sleepTimerMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepTimerEndTime by remember { mutableStateOf<Long?>(null) }
    var sleepTimerMessage by remember { mutableStateOf<String?>(null) }
    // Keep TV's expanded-lyrics mode independent from mobile and voice-command UI state.
    val (showLyrics, onShowLyricsChange) = com.auramusic.app.utils.rememberPreference(
        com.auramusic.app.constants.TvShowLyricsKey,
        false,
    )
    // On TV, enabling lyrics always means expanded mode. The preference survives
    // navigation, so returning to the player restores the same expanded view.
    val lyricsExpanded = showLyrics

    // Fullscreen media overlay: the bottom controls fade out after a few idle
    // seconds and any remote key press wakes them again — the wake counter
    // restarts the hide timer.
    var controlsVisible by remember { mutableStateOf(true) }
    var wakeTick by remember { mutableStateOf(0) }
    LaunchedEffect(isFullScreen, wakeTick) {
        if (!isFullScreen) return@LaunchedEffect
        controlsVisible = true
        delay(5000)
        controlsVisible = false
    }
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        label = "fullscreenControls",
    )
    // Fullscreen transport controls are driven from the remote rather than a pointer,
    // so left/right walks the row itself instead of hoping the focus search picks the
    // right neighbour: the row is the only thing on screen holding focus, and the
    // search would otherwise have nowhere to go once it reached either end.
    val transportFocus = remember {
        List(FULLSCREEN_TRANSPORT_SLOTS) { FocusRequester() }
    }
    // The queue panel's own close button, so the remote can leave the list without
    // having to walk all the way back to the row underneath it.
    val queueCloseFocus = remember { FocusRequester() }
    // One requester per queue row, kept across recompositions so the panel can put the
    // remote back on the row that was playing when it is opened.
    val queueItemFocusers = remember { mutableStateMapOf<String, FocusRequester>() }
    fun queueFocuserFor(mediaId: String): FocusRequester =
        queueItemFocusers.getOrPut(mediaId) { FocusRequester() }
    // Which button the remote is on. Kept as state rather than read off focus so the
    // row can move focus without racing the focus system's own bookkeeping.
    var focusedTransport by remember { mutableStateOf(FULLSCREEN_PLAY_SLOT) }
    // Waking the overlay when a button takes focus: the remote has just proved the
    // listener is still watching, and controls that stay faded out are controls that
    // cannot be aimed at.
    fun wakeControls() {
        controlsVisible = true
        wakeTick++
    }

    // Resolve player connection: prefer passed-in parameter, fall back to composition local.
    // We avoid early return to show loading UI when service not ready.
    val effectivePlayerConnection = playerConnection ?: LocalPlayerConnection.current

    // Collect state from the resolved connection; if null, show loading/empty state
    val currentSong by effectivePlayerConnection?.currentSong?.collectAsState(null) ?: remember { mutableStateOf(null) }
    val currentMediaMetadata by effectivePlayerConnection?.mediaMetadata?.collectAsState(null) ?: remember { mutableStateOf(null) }
    val isPlaying by effectivePlayerConnection?.isPlaying?.collectAsState(false) ?: remember { mutableStateOf(false) }
    val queueWindows by effectivePlayerConnection?.queueWindows?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val currentWindowIndex by effectivePlayerConnection?.currentMediaItemIndex?.collectAsState() ?: remember { mutableStateOf(0) }
    val videoModeToggleEnabled by com.auramusic.app.utils.rememberPreference(
        VideoModeEnabledKey,
        defaultValue = true,
    )
    val videoModeEnabled by effectivePlayerConnection?.videoModeEnabled?.collectAsState(false) ?: remember { mutableStateOf(false) }
    val isVideoAvailable by effectivePlayerConnection?.isVideoAvailable?.collectAsState(false) ?: remember { mutableStateOf(false) }
    val isVideoSwitching by effectivePlayerConnection?.isVideoSwitching?.collectAsState(false) ?: remember { mutableStateOf(false) }
    val playbackState by effectivePlayerConnection?.playbackState?.collectAsState(androidx.media3.common.Player.STATE_IDLE)
        ?: remember { mutableStateOf(androidx.media3.common.Player.STATE_IDLE) }
    val serviceSleepTimer = effectivePlayerConnection?.service?.sleepTimer
    val sleepTimerActive = serviceSleepTimer?.isActive == true

    val currentLyrics by (effectivePlayerConnection?.currentLyrics?.collectAsState(initial = null)
        ?: remember { mutableStateOf(null) })

    // Local alias for concise usage throughout the composable
    val pc = effectivePlayerConnection
    val activePlayer by (pc?.service?.playerFlow?.collectAsState(initial = null)
        ?: remember { mutableStateOf(null) })

    // Focus requesters for TV navigation
    val playButtonFocus = remember { FocusRequester() }
    val queueItemFocus = remember { FocusRequester() }

    // The slots the remote walks, in the order they sit on screen. The lyrics toggle
    // has nowhere to go while a video owns the picture, so the row closes up around
    // it instead of stepping onto a button that was never drawn.
    val transportSlots = remember(videoModeEnabled) {
        buildList {
            add(FULLSCREEN_PREVIOUS_SLOT)
            add(FULLSCREEN_REWIND_SLOT)
            add(FULLSCREEN_PLAY_SLOT)
            add(FULLSCREEN_FORWARD_SLOT)
            add(FULLSCREEN_NEXT_SLOT)
            if (!videoModeEnabled) add(FULLSCREEN_LYRICS_SLOT)
            add(FULLSCREEN_QUEUE_SLOT)
            add(FULLSCREEN_EXIT_SLOT)
        }
    }

    /** Moves the remote one button left or right along the drawn row. */
    fun moveTransportFocus(delta: Int) {
        val position = transportSlots.indexOf(focusedTransport)
            .takeIf { it >= 0 }
            ?: transportSlots.indexOf(FULLSCREEN_PLAY_SLOT)
        val target = transportSlots[(position + delta).coerceIn(0, transportSlots.lastIndex)]
        focusedTransport = target
        wakeControls()
        runCatching { transportFocus[target].requestFocus() }
    }

    fun focusTransportSlot(slot: Int) {
        focusedTransport = slot
        wakeControls()
        runCatching { transportFocus[slot].requestFocus() }
    }

    LaunchedEffect(isFullScreen) {
        if (isFullScreen) {
            delay(120)
            // Play/pause is where the remote starts: it is the one control every
            // viewer reaches for first.
            focusTransportSlot(FULLSCREEN_PLAY_SLOT)
        }
    }

    // Opening the queue hands the remote to it: the row that is playing takes focus
    // so up and down walk the list from a known place, rather than starting the
    // listener back at the top of a queue they cannot see.
    LaunchedEffect(fullscreenQueueOpen, currentWindowIndex) {
        if (!fullscreenQueueOpen) return@LaunchedEffect
        delay(150)
        val playing = queueWindows.getOrNull(currentWindowIndex)?.mediaItem?.mediaId
        val requester = playing?.let { queueItemFocusers[it] }
            ?: queueWindows.firstOrNull()?.mediaItem?.mediaId?.let { queueItemFocusers[it] }
        if (requester != null) runCatching { requester.requestFocus() }
    }

    LaunchedEffect(Unit) {
        // Request focus on play button initially
        runCatching { playButtonFocus.requestFocus() }
    }

    LaunchedEffect(sleepTimerActive) {
        if (!sleepTimerActive) {
            sleepTimerMinutes = null
            sleepTimerEndTime = null
        }
    }

    LaunchedEffect(sleepTimerMessage) {
        if (sleepTimerMessage != null) {
            delay(1800)
            sleepTimerMessage = null
        }
    }

    LaunchedEffect(activePlayer) {
        val player = activePlayer ?: return@LaunchedEffect
        while (true) {
            delay(250)
            val playbackPosition = runCatching {
                (player.duration.takeIf { it != C.TIME_UNSET } ?: 0L) to player.currentPosition
            }.getOrNull() ?: break
            duration = playbackPosition.first
            currentPosition = playbackPosition.second
        }
    }

    // Fetch lyrics when song changes. Use player metadata as a fallback so TV
    // playback that starts before the song row is present in the local DB still
    // has player artwork, lyrics and video state.
    val mediaMetadata = currentSong?.toMediaMetadata() ?: currentMediaMetadata

    LaunchedEffect(mediaMetadata?.id, mediaMetadata?.isVideoSong, videoModeToggleEnabled) {
        val videoId = mediaMetadata?.id ?: return@LaunchedEffect
        val playerConnection = pc ?: return@LaunchedEffect

        // If video mode is already active in the service, never re-trigger it.
        // The PlayerView update block handles surface re-attachment on re-entry.
        // Re-calling enableVideoMode on re-entry pauses playback, shows loading,
        // and causes the black-screen-then-loading bug on the 2nd+ re-entry.
        if (videoModeEnabled) return@LaunchedEffect

        // Fast path for TV: if this is a known video song, enable video mode immediately
        // without waiting for the network availability check. The check is still done
        // in parallel to disable if unavailable, but the video starts fetching right away.
        if (videoModeToggleEnabled && mediaMetadata.isVideoSong) {
            playerConnection.enableVideoMode(true)
            // Verify availability in background; if not actually available, fall back to audio.
            launch(kotlinx.coroutines.Dispatchers.IO) {
                val available = playerConnection.service.checkVideoAvailability(videoId)
                val currentId = playerConnection.mediaMetadata.value?.id
                if (currentId == videoId && !available) {
                    playerConnection.enableVideoMode(false)
                }
            }
            return@LaunchedEffect
        }

        val available = playerConnection.service.checkVideoAvailability(videoId)

        // Staleness check: song may have changed during network call
        val currentId = playerConnection.mediaMetadata.value?.id
        if (currentId != videoId) return@LaunchedEffect

        if (videoModeToggleEnabled && mediaMetadata.isVideoSong && available) {
            playerConnection.enableVideoMode(true)
        } else if (videoModeEnabled && !mediaMetadata.isVideoSong) {
            playerConnection.enableVideoMode(false)
        }
    }

    // TV-specific: fetch lyrics fresh per song without database storage.
    // The mobile path uses MusicService + database caching, but on TV we
    // bypass the database (so storage doesn't accumulate) and push the
    // result directly into PlayerConnection.tvLyricsFlow.
    val context = LocalContext.current
    LaunchedEffect(mediaMetadata?.id, showLyrics) {
        if (!showLyrics || mediaMetadata == null || pc == null) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    com.auramusic.app.di.LyricsHelperEntryPoint::class.java
                )
                val lyricsHelper = entryPoint.lyricsHelper()
                val fetched = lyricsHelper.getLyrics(mediaMetadata)
                pc.updateTvLyrics(mediaMetadata.id, fetched.lyrics, fetched.provider)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                pc.updateTvLyrics(
                    mediaMetadata.id,
                    com.auramusic.app.db.entities.LyricsEntity.LYRICS_NOT_FOUND,
                    "",
                )
            }
        }
    }

    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background with album art blur (full screen)
            mediaMetadata?.thumbnailUrl?.let { thumbnailUrl ->
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.7f),
                                    Color.Black.copy(alpha = 0.9f)
                                )
                            )
                        )
                )
            } ?: Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
            )

            sleepTimerMessage?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(1f)
                        .padding(top = 32.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.95f),
                    tonalElevation = 8.dp,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                    )
                }
            }

            if (!isFullScreen) {
            // Main content: Two-column layout. Some TVs expose less usable
            // height because of overscan/scaling, so compact the left player
            // column to keep playback controls visible on every TV.
            Box(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val compactHeight = maxHeight < 720.dp
                val veryCompact = maxHeight < 540.dp
                val contentPadding = if (veryCompact) 8.dp else if (compactHeight) 16.dp else 24.dp
                val contentSpacing = if (veryCompact) 8.dp else if (compactHeight) 20.dp else 32.dp
                val playerVerticalSpacing = if (veryCompact) 4.dp else if (compactHeight) 12.dp else 24.dp
                val topSpacerHeight = if (veryCompact) 4.dp else if (compactHeight) 16.dp else 48.dp
                val artworkSize = if (veryCompact) 120.dp else if (compactHeight) 200.dp else 280.dp
                val controlSpacing = if (veryCompact) 4.dp else if (compactHeight) 8.dp else 16.dp
                val secondaryTopPadding = if (veryCompact) 2.dp else if (compactHeight) 8.dp else 16.dp
                val sideControlSize = if (veryCompact) 44.dp else if (compactHeight) 56.dp else 72.dp
                val playControlSize = if (veryCompact) 52.dp else if (compactHeight) 68.dp else 80.dp

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(contentSpacing),
                verticalAlignment = Alignment.Top,
            ) {
                // LEFT SIDE: Player controls (40% width, or 55% on very compact)
                Column(
                    modifier = Modifier
                        .weight(if (veryCompact) 0.55f else 0.4f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(playerVerticalSpacing),
                ) {
                    Spacer(modifier = Modifier.height(topSpacerHeight)) // Space for top bar

                    // Album art / Lyrics / Video container
                    if (lyricsExpanded && showLyrics) {
                        // Expanded lyrics mode — lyrics fill the entire left column
                            val positionProvider = { effectivePlayerConnection?.player?.currentPosition ?: currentPosition }
                        val lyrics = remember(currentLyrics) { currentLyrics?.lyrics?.trim() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                lyrics == null -> {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                                }
                                lyrics == com.auramusic.app.db.entities.LyricsEntity.LYRICS_NOT_FOUND -> {
                                    Text("Lyrics not found", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                                }
                                else -> {
                                    androidx.compose.material3.ProvideTextStyle(
                                        value = MaterialTheme.typography.bodyMedium.copy(fontSize = 20.sp, textAlign = TextAlign.Center, color = Color(0xFFFFFBFE).copy(alpha = 0.98f))
                                    ) {
                                        com.auramusic.app.ui.component.Lyrics(
                                            sliderPositionProvider = positionProvider,
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                                            showLyrics = true,
                                            disableInteractiveFeatures = true
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                    // Video fills the available left panel; artwork keeps its thumbnail size.
                    Box(
                        modifier = (if (videoModeEnabled) {
                            Modifier.fillMaxWidth().weight(1f)
                        } else {
                            Modifier.size(artworkSize)
                        })
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val isVideoBuffering = videoModeEnabled &&
                            playbackState == androidx.media3.common.Player.STATE_BUFFERING

                        if (videoModeEnabled) {
                            TvVideoView(
                                activePlayer = activePlayer,
                                isVideoSwitching = isVideoSwitching,
                                isBuffering = isVideoBuffering,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (showLyrics) {
                            // Show lyrics behind thumbnail when enabled
                            val positionProvider = { effectivePlayerConnection?.player?.currentPosition ?: currentPosition }

                            val lyrics = remember(currentLyrics) { currentLyrics?.lyrics?.trim() }

                            when {
                                lyrics == null -> {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                                lyrics == com.auramusic.app.db.entities.LyricsEntity.LYRICS_NOT_FOUND -> {
                                    Text(
                                        text = "Lyrics not found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                else -> {
                                    androidx.compose.material3.ProvideTextStyle(
                                        value = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 18.sp,
                                            textAlign = TextAlign.Center,
                                            color = Color(0xFFFFFBFE).copy(alpha = 0.98f) // Brighter white with high alpha
                                        )
                                    ) {
                                        com.auramusic.app.ui.component.Lyrics(
                                            sliderPositionProvider = positionProvider,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 16.dp),
                                            showLyrics = true,
                                            disableInteractiveFeatures = true
                                        )
                                    }
                                }
                            }
                        } else {
                            // Show album art when lyrics are disabled
                            mediaMetadata?.let { metadata ->
                                AsyncImage(
                                    model = metadata.thumbnailUrl,
                                    contentDescription = metadata.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            // Subtle visualizer overlay at the bottom of album art
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .align(Alignment.BottomCenter)
                            ) {
                                TvAudioVisualizer(
                                    isPlaying = isPlaying,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                        .padding(horizontal = 24.dp),
                                    barCount = 20,
                                    barColor = Color.White.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                    } // end else (normal mode)

                    // Song info
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    Text(
                        text = mediaMetadata?.title ?: "No song playing",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(
                            iterations = 1,
                            initialDelayMillis = 3000,
                            velocity = 30.dp
                        )
                    )
                        if (mediaMetadata?.explicit == true) {
                            Icon(
                                painter = painterResource(com.auramusic.app.R.drawable.explicit),
                                contentDescription = "Explicit",
                                modifier = Modifier.size(22.dp),
                                tint = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }

                        Text(
                            text = mediaMetadata?.artists?.joinToString(", ") { it.name }.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee(
                                iterations = 1,
                                initialDelayMillis = 3000,
                                velocity = 30.dp
                            )
                        )
                    }

                    // Progress bar and time
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = makeTimeString(currentPosition),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                            Text(
                                text = makeTimeString(duration),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }

                    // Playback controls
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(controlSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvPlayerControlButton(
                            onClick = { pc?.seekToPrevious() },
                            icon = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous song",
                            size = sideControlSize,
                        )

                        TvPlayerControlButton(
                            onClick = {
                                val newPos = maxOf(0L, currentPosition - 10000L)
                                pc?.player?.seekTo(newPos)
                            },
                            icon = Icons.Filled.FastRewind,
                            contentDescription = "Rewind 10 seconds",
                            size = sideControlSize,
                        )

                        TvPlayerControlButton(
                            onClick = { pc?.togglePlayPause() },
                            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            size = playControlSize,
                            focusRequester = playButtonFocus,
                        )

                        TvPlayerControlButton(
                            onClick = {
                                val durationVal = pc?.player?.duration?.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                                val newPos = minOf(durationVal, currentPosition + 10000L)
                                pc?.player?.seekTo(newPos)
                            },
                            icon = Icons.Filled.FastForward,
                            contentDescription = "Fast forward 10 seconds",
                            size = sideControlSize,
                        )

                        TvPlayerControlButton(
                            onClick = { pc?.seekToNext() },
                            icon = Icons.Filled.SkipNext,
                            contentDescription = "Next song",
                            size = sideControlSize,
                        )
                    }

                    // Secondary controls
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(controlSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = secondaryTopPadding)
                    ) {
                        TvPlayerControlButton(
                            onClick = { pc?.toggleShuffle() },
                            icon = Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (pc?.shuffleModeEnabled?.value == true)
                                MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                            size = sideControlSize,
                        )

                        TvPlayerControlButton(
                            onClick = {
                                pc?.player?.let { player ->
                                    val currentMode = player.repeatMode
                                    val newMode = when (currentMode) {
                                        androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                                        androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                                        else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                                    }
                                    player.repeatMode = newMode
                                }
                            },
                            icon = when (pc?.repeatMode?.value) {
                                androidx.media3.common.Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                                else -> Icons.Filled.Repeat
                            },
                            contentDescription = "Repeat",
                            tint = if (pc?.repeatMode?.value != androidx.media3.common.Player.REPEAT_MODE_OFF)
                                MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                            size = sideControlSize,
                        )

                        TvPlayerControlButton(
                            onClick = {
                                val currentMinutes = sleepTimerMinutes
                                val newMinutes = when (currentMinutes) {
                                    null -> 15
                                    15 -> 30
                                    30 -> 60
                                    60 -> 120
                                    else -> null
                                }

                                if (newMinutes != null) {
                                    sleepTimerEndTime = System.currentTimeMillis() + (newMinutes * 60 * 1000L)
                                    sleepTimerMinutes = newMinutes
                                    pc?.service?.sleepTimer?.start(newMinutes)
                                    sleepTimerMessage = "Sleep timer: $newMinutes minutes"
                                } else {
                                    sleepTimerEndTime = null
                                    sleepTimerMinutes = null
                                    pc?.service?.sleepTimer?.clear()
                                    sleepTimerMessage = "Sleep timer off"
                                }
                            },
                            painter = painterResource(R.drawable.bedtime),
                            contentDescription = if (sleepTimerActive) "Cancel sleep timer" else "Set sleep timer",
                            tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                            size = sideControlSize,
                        )

                        TvPlayerControlButton(
                            onClick = {
                                onShowLyricsChange(!showLyrics)
                            },
                            icon = Icons.Filled.Lyrics,
                            contentDescription = if (showLyrics) "Show album artwork" else "Show expanded lyrics",
                            tint = if (showLyrics) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                            size = sideControlSize,
                        )

                        val isLiked = currentSong?.song?.liked == true
                        TvPlayerControlButton(
                            onClick = { pc?.toggleLike() },
                            painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                            contentDescription = if (isLiked) "Remove from liked songs" else "Add to liked songs",
                            tint = if (isLiked) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.7f),
                            size = sideControlSize,
                        )
                    }
                }

                // RIGHT SIDE: Queue (60% width) - always visible
                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight(),
                ) {
                    Text(
                        text = "Now Playing",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            count = queueWindows.size,
                            key = { index -> "${queueWindows.getOrNull(index)?.mediaItem?.mediaId ?: "unknown"}_$index" }
                        ) { index ->
                            val window = queueWindows.getOrNull(index)
                            val isCurrentSong = index == currentWindowIndex

                            if (window != null) {
                                TvQueueItem(
                                    window = window,
                                    isCurrentSong = isCurrentSong,
                                    onClick = {
                                        pc?.player?.seekTo(index, C.TIME_UNSET)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        if (queueWindows.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "Queue is empty",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White.copy(alpha = 0.6f),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The way out to fullscreen is a fixed button rather than one more row of
            // controls, because that row is exactly what a full-height video or a
            // column of lyrics pushes off the bottom of a short screen: it was
            // reachable with artwork, and unreachable exactly when it was wanted.
            // Pinned to the corner under the app bar, and on the player side of the
            // split rather than over the queue, so it works the same for video, for
            // lyrics and for artwork without covering what is queued.
            TvPlayerControlButton(
                onClick = { onFullScreenChange(true) },
                painter = painterResource(R.drawable.fullscreen),
                contentDescription = "Enter fullscreen",
                size = if (compactHeight) 56.dp else 68.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = if (compactHeight) 84.dp else 104.dp, start = 24.dp),
            )

            // Back button removed from here — now in the top bar
            }
            }
            }

            if (isFullScreen) {
                // Full-bleed media with auto-hiding controls overlaid. The split
                // layout is not composed in this state, so this is the only
                // PlayerView instance and it owns the video surface. The parent
                // top bar is hidden too (see TvApp), making this truly edge-to-edge.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(3f)
                        .background(Color.Black)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            wakeTick++
                            // Left/right walk the transport row by hand. The row is the
                            // only thing on the screen that holds focus, so leaving it to
                            // the focus search gives the remote nowhere to go once it
                            // reaches either end.
                            when (event.key) {
                                Key.DirectionLeft, Key.DirectionRight -> {
                                    // The open queue is a panel of its own: left and
                                    // right stay inside it rather than walking back to
                                    // a row that is behind it. Centre is left alone so
                                    // the row under the remote is the one that is
                                    // pressed when the listener presses select.
                                    if (fullscreenQueueOpen) {
                                        true
                                    } else {
                                        moveTransportFocus(
                                            if (event.key == Key.DirectionLeft) -1 else 1,
                                        )
                                        true
                                    }
                                }
                                else -> false
                            }
                        },
                ) {
                    when {
                        videoModeEnabled -> TvVideoView(
                            activePlayer = activePlayer,
                            isVideoSwitching = isVideoSwitching,
                            isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING,
                            modifier = Modifier.fillMaxSize(),
                        )

                        showLyrics -> {
                            val positionProvider = { effectivePlayerConnection?.player?.currentPosition ?: currentPosition }
                            val lyrics = remember(currentLyrics) { currentLyrics?.lyrics?.trim() }
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    lyrics == null -> {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            modifier = Modifier.size(48.dp),
                                        )
                                    }

                                    lyrics == com.auramusic.app.db.entities.LyricsEntity.LYRICS_NOT_FOUND -> {
                                        Text(
                                            text = "Lyrics not found",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Color.White.copy(alpha = 0.7f),
                                            textAlign = TextAlign.Center,
                                        )
                                    }

                                    else -> {
                                        androidx.compose.material3.ProvideTextStyle(
                                            value = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = 26.sp,
                                                textAlign = TextAlign.Center,
                                                color = Color(0xFFFFFBFE).copy(alpha = 0.98f),
                                            ),
                                        ) {
                                            com.auramusic.app.ui.component.Lyrics(
                                                sliderPositionProvider = positionProvider,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 64.dp, vertical = 96.dp),
                                                showLyrics = true,
                                                disableInteractiveFeatures = true,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        else -> mediaMetadata?.thumbnailUrl?.let { thumbnailUrl ->
                            AsyncImage(
                                model = thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // Scrim keeps the overlaid controls readable over bright video.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(300.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                ),
                            ),
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .graphicsLayer { alpha = controlsAlpha }
                            .padding(horizontal = 48.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = mediaMetadata?.title ?: "No song playing",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    iterations = 1,
                                    initialDelayMillis = 3000,
                                    velocity = 30.dp,
                                ),
                        )
                        Text(
                            text = mediaMetadata?.artists?.joinToString(", ") { it.name }.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    iterations = 1,
                                    initialDelayMillis = 3000,
                                    velocity = 30.dp,
                                ),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = makeTimeString(currentPosition),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                            Text(
                                text = makeTimeString(duration),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvPlayerControlButton(
                                onClick = { pc?.seekToPrevious() },
                                icon = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous song",
                                focusRequester = transportFocus[FULLSCREEN_PREVIOUS_SLOT],
                                onFocused = {
                                    focusedTransport = FULLSCREEN_PREVIOUS_SLOT
                                    wakeControls()
                                },
                            )
                            TvPlayerControlButton(
                                onClick = {
                                    val newPos = maxOf(0L, currentPosition - 10000L)
                                    pc?.player?.seekTo(newPos)
                                },
                                icon = Icons.Filled.FastRewind,
                                contentDescription = "Rewind 10 seconds",
                                focusRequester = transportFocus[FULLSCREEN_REWIND_SLOT],
                                onFocused = {
                                    focusedTransport = FULLSCREEN_REWIND_SLOT
                                    wakeControls()
                                },
                            )
                            TvPlayerControlButton(
                                onClick = { pc?.togglePlayPause() },
                                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                size = 88.dp,
                                focusRequester = transportFocus[FULLSCREEN_PLAY_SLOT],
                                onFocused = {
                                    focusedTransport = FULLSCREEN_PLAY_SLOT
                                    wakeControls()
                                },
                            )
                            TvPlayerControlButton(
                                onClick = {
                                    val durationVal = pc?.player?.duration?.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                                    val newPos = minOf(durationVal, currentPosition + 10000L)
                                    pc?.player?.seekTo(newPos)
                                },
                                icon = Icons.Filled.FastForward,
                                contentDescription = "Fast forward 10 seconds",
                                focusRequester = transportFocus[FULLSCREEN_FORWARD_SLOT],
                                onFocused = {
                                    focusedTransport = FULLSCREEN_FORWARD_SLOT
                                    wakeControls()
                                },
                            )
                            TvPlayerControlButton(
                                onClick = { pc?.seekToNext() },
                                icon = Icons.Filled.SkipNext,
                                contentDescription = "Next song",
                                focusRequester = transportFocus[FULLSCREEN_NEXT_SLOT],
                                onFocused = {
                                    focusedTransport = FULLSCREEN_NEXT_SLOT
                                    wakeControls()
                                },
                            )
                            if (!videoModeEnabled) {
                                TvPlayerControlButton(
                                    onClick = { onShowLyricsChange(!showLyrics) },
                                    icon = Icons.Filled.Lyrics,
                                    contentDescription = if (showLyrics) "Show album artwork" else "Show lyrics",
                                    tint = if (showLyrics) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                    focusRequester = transportFocus[FULLSCREEN_LYRICS_SLOT],
                                    onFocused = {
                                        focusedTransport = FULLSCREEN_LYRICS_SLOT
                                        wakeControls()
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            // Kebab: opens and closes the queue over the media. Everything
                            // the queue offers stays reachable behind it - closing it puts
                            // the remote back on this button rather than at the far end of
                            // the row.
                            TvPlayerControlButton(
                                onClick = {
                                    fullscreenQueueOpen = !fullscreenQueueOpen
                                    if (!fullscreenQueueOpen) {
                                        focusTransportSlot(FULLSCREEN_QUEUE_SLOT)
                                    }
                                },
                                icon = if (fullscreenQueueOpen) Icons.Filled.MenuOpen else Icons.Filled.MoreVert,
                                contentDescription = if (fullscreenQueueOpen) "Close queue" else "Open queue",
                                tint = if (fullscreenQueueOpen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White
                                },
                                focusRequester = transportFocus[FULLSCREEN_QUEUE_SLOT],
                                onFocused = {
                                    focusedTransport = FULLSCREEN_QUEUE_SLOT
                                    wakeControls()
                                },
                            )
                            TvPlayerControlButton(
                                onClick = { onFullScreenChange(false) },
                                painter = painterResource(R.drawable.fullscreen_exit),
                                contentDescription = "Exit fullscreen",
                                focusRequester = transportFocus[FULLSCREEN_EXIT_SLOT],
                                onFocused = {
                                    focusedTransport = FULLSCREEN_EXIT_SLOT
                                    wakeControls()
                                },
                            )
                        }
                    }

                    if (fullscreenQueueOpen) {
                        // The queue, folded out over the media. It takes the whole right
                        // edge so a title is never clipped by the panel, and every row is
                        // focusable and selectable from the remote.
                        Surface(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(460.dp)
                                .zIndex(4f),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                            tonalElevation = 6.dp,
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                                ) {
                                    Text(
                                        text = "Queue",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = makeTimeString(duration),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    TvPlayerControlButton(
                                        onClick = {
                                            fullscreenQueueOpen = false
                                            focusTransportSlot(FULLSCREEN_QUEUE_SLOT)
                                        },
                                        icon = Icons.Filled.Close,
                                        contentDescription = "Close queue",
                                        size = 48.dp,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        focusRequester = queueCloseFocus,
                                        onFocused = { wakeControls() },
                                    )
                                }

                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(
                                        count = queueWindows.size,
                                        key = { index ->
                                            "${queueWindows.getOrNull(index)?.mediaItem?.mediaId ?: "unknown"}_$index"
                                        },
                                    ) { index ->
                                        val window = queueWindows.getOrNull(index)
                                        val isCurrentSong = index == currentWindowIndex
                                        if (window != null) {
                                            val mediaId = window.mediaItem.mediaId
                                            TvQueueItem(
                                                window = window,
                                                isCurrentSong = isCurrentSong,
                                                onClick = {
                                                    fullscreenQueueOpen = false
                                                    pc?.player?.seekTo(index, C.TIME_UNSET)
                                                    focusTransportSlot(FULLSCREEN_PLAY_SLOT)
                                                },
                                                focusRequester = queueFocuserFor(mediaId),
                                                onFocused = { wakeControls() },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp),
                                            )
                                        }
                                    }

                                    if (queueWindows.isEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(32.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = "Queue is empty",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    textAlign = TextAlign.Center,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
