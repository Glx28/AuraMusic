/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.auramusic.app.LocalPlayerAwareWindowInsets
import com.auramusic.app.LocalPlayerConnection
import com.auramusic.app.R
import com.auramusic.app.constants.MaxImageCacheSizeKey
import com.auramusic.app.constants.MaxSongCacheSizeKey
import com.auramusic.app.extensions.tryOrNull
import com.auramusic.app.ui.component.ActionPromptDialog
import com.auramusic.app.ui.component.IconButton
import com.auramusic.app.ui.component.Material3SettingsGroup
import com.auramusic.app.ui.component.Material3SettingsItem
import com.auramusic.app.ui.utils.backToMain
import com.auramusic.app.ui.utils.formatFileSize
import com.auramusic.app.utils.rememberPreference
import com.auramusic.app.video.VideoDownloadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalCoilApi::class, ExperimentalMaterial3Api::class, DelicateCoilApi::class)
@Composable
fun StorageSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val imageDiskCache = context.imageLoader.diskCache ?: return
    val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return
    val downloadCache = LocalPlayerConnection.current?.service?.downloadCache ?: return

    val coroutineScope = rememberCoroutineScope()
    val songCacheString = stringResource(R.string.song_cache).lowercase()
    val imageCacheString = stringResource(R.string.image_cache).lowercase()
    val (maxImageCacheSize, onMaxImageCacheSizeChange) = rememberPreference(
        key = MaxImageCacheSizeKey,
        defaultValue = 512
    )
    val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(
        key = MaxSongCacheSizeKey,
        defaultValue = 1024
    )

    var clearDownloads by remember { mutableStateOf(false) }
    var clearCacheDialog by remember { mutableStateOf(false) }
    var clearImageCacheDialog by remember { mutableStateOf(false) }
    var clearVideoDownloadsDialog by remember { mutableStateOf(false) }

    // Videos are kept by a service of their own, in files of their own, so their place
    // on the device is measured separately and cleared separately. Asking the music
    // cache to account for them answers a question nobody asked.
    LaunchedEffect(Unit) { VideoDownloadStore.load(context) }
    val videoDownloads by VideoDownloadStore.entries(context).collectAsState()
    val pendingVideoDownloads by VideoDownloadStore.pendingFlow(context).collectAsState()
    var videoDiskUsage by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // Measured on a timer rather than per state change: progress lands many times a
        // second while something is downloading, and re-walking the folder on each of
        // those would cost far more than the answer is worth.
        while (isActive) {
            videoDiskUsage = withContext(Dispatchers.IO) { VideoDownloadStore.diskUsage(context) }
            delay(1500)
        }
    }

    // State for the confirmation dialog
    var showCacheWarningDialog by remember { mutableStateOf(false) }
    var cacheType by remember { mutableStateOf("") }
    var cacheUsage by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var onConfirmAction by remember { mutableStateOf<() -> Unit>({}) }


    var imageCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(imageDiskCache.size)
    }
    var playerCacheSize by remember {
        androidx.compose.runtime.mutableLongStateOf(tryOrNull { playerCache.cacheSpace } ?: 0)
    }
    var downloadCacheSize by remember {
        mutableLongStateOf(tryOrNull { downloadCache.cacheSpace } ?: 0)
    }
    val imageCacheProgress by animateFloatAsState(
        targetValue = (imageCacheSize.toFloat() / (maxImageCacheSize * 1024 * 1024L)).coerceIn(
            0f,
            1f
        ),
        label = "imageCacheProgress",
    )
    val playerCacheProgress by animateFloatAsState(
        targetValue = (playerCacheSize.toFloat() / (maxSongCacheSize * 1024 * 1024L)).coerceIn(
            0f,
            1f
        ),
        label = "playerCacheProgress",
    )

    LaunchedEffect(maxImageCacheSize) {
        SingletonImageLoader.reset()
        if (maxImageCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                imageDiskCache.clear()
            }
        }
    }
    LaunchedEffect(maxSongCacheSize) {
        if (maxSongCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                playerCache.keys.forEach { key ->
                    playerCache.removeResource(key)
                }
            }
        }
    }

    LaunchedEffect(imageDiskCache) {
        while (isActive) {
            delay(500)
            imageCacheSize = imageDiskCache.size
        }
    }
    LaunchedEffect(playerCache) {
        while (isActive) {
            delay(500)
            playerCacheSize = tryOrNull { playerCache.cacheSpace } ?: 0
        }
    }
    LaunchedEffect(downloadCache) {
        while (isActive) {
            delay(500)
            downloadCacheSize = tryOrNull { downloadCache.cacheSpace } ?: 0
        }
    }

    if (clearDownloads) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_all_downloads),
            onDismiss = { clearDownloads = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    downloadCache.keys.forEach { key ->
                        downloadCache.removeResource(key)
                    }
                }
                clearDownloads = false
            },
            onCancel = { clearDownloads = false },
            content = {
                Text(text = stringResource(R.string.clear_downloads_dialog))
            }
        )
    }
    if (clearCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_song_cache),
            onDismiss = { clearCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    playerCache.keys.forEach { key ->
                        playerCache.removeResource(key)
                    }
                }
                clearCacheDialog = false
            },
            onCancel = { clearCacheDialog = false },
            content = {
                Text(text = stringResource(R.string.clear_song_cache_dialog))
            }
        )
    }
    if (clearImageCacheDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_image_cache),
            onDismiss = { clearImageCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    imageDiskCache.clear()
                }
                clearImageCacheDialog = false
            },
            onCancel = { clearImageCacheDialog = false },
            content = {
                Text(text = stringResource(R.string.clear_image_cache_dialog))
            }
        )
    }
    if (clearVideoDownloadsDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.clear_video_downloads),
            onDismiss = { clearVideoDownloadsDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    VideoDownloadStore.removeAll(context)
                }
                clearVideoDownloadsDialog = false
            },
            onCancel = { clearVideoDownloadsDialog = false },
            content = {
                Text(text = stringResource(R.string.clear_video_downloads_dialog))
            },
        )
    }

    // Confirmation Dialog
    if (showCacheWarningDialog) {
        AlertDialog(
            onDismissRequest = { showCacheWarningDialog = false },
            title = { Text(stringResource(R.string.cache_size_warning_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.cache_size_warning_message,
                        formatFileSize(cacheUsage),
                        cacheType
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmAction()
                        showCacheWarningDialog = false
                    }
                ) {
                    Text(
                        stringResource(R.string.cache_size_warning_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCacheWarningDialog = false }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            }
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )
            Material3SettingsGroup(
                title = stringResource(R.string.storage),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.storage),
                        title = { Text(stringResource(R.string.downloaded_songs)) },
                        description = {
                            Text(text = formatFileSize(downloadCacheSize))
                        }
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_all_downloads)) },
                        onClick = {
                            clearDownloads = true
                        }
                    )
                )
            )

            Material3SettingsGroup(
                title = stringResource(R.string.videos_section),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.slow_motion_video),
                        title = { Text(stringResource(R.string.video_downloads_storage)) },
                        description = {
                            Text(
                                text = when {
                                    // Still arriving: the number on screen is the room
                                    // the videos take right now, and the count says how
                                    // many of them are mid-transfer.
                                    pendingVideoDownloads.isNotEmpty() -> stringResource(
                                        R.string.video_downloads_storage_pending,
                                        formatFileSize(videoDiskUsage),
                                        pendingVideoDownloads.size,
                                    )

                                    videoDownloads.isNotEmpty() -> stringResource(
                                        R.string.video_downloads_storage_summary,
                                        formatFileSize(videoDiskUsage),
                                        videoDownloads.size,
                                    )

                                    else -> stringResource(R.string.video_downloads_none)
                                },
                            )
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_video_downloads)) },
                        // Nothing to clear is not a button that does nothing: there is
                        // no video on the device to remove.
                        enabled = videoDownloads.isNotEmpty() || pendingVideoDownloads.isNotEmpty(),
                        onClick = { clearVideoDownloadsDialog = true },
                    ),
                ),
            )

            Material3SettingsGroup(
                title = stringResource(R.string.song_cache),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.cached),
                        title = { Text(stringResource(R.string.max_song_cache_size)) },
                        description = {
                            val songCacheValues =
                                remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1) }
                            Column {
                                Text(
                                    text = when (maxSongCacheSize) {
                                        0 -> stringResource(R.string.disable)
                                        -1 -> stringResource(R.string.unlimited)
                                        else -> formatFileSize(maxSongCacheSize * 1024 * 1024L)
                                    }
                                )
                                Slider(
                                    value = songCacheValues.indexOf(maxSongCacheSize).toFloat(),
                                    onValueChange = {
                                        val newValue = songCacheValues[it.roundToInt()]
                                        val newLimitInBytes = if (newValue == -1) {
                                            Long.MAX_VALUE
                                        } else {
                                            newValue * 1024 * 1024L
                                        }

                                        if (newLimitInBytes < playerCacheSize) {
                                            cacheUsage = playerCacheSize
                                            cacheType = songCacheString
                                            onConfirmAction = { onMaxSongCacheSizeChange(newValue) }
                                            showCacheWarningDialog = true
                                        } else {
                                            onMaxSongCacheSizeChange(newValue)
                                        }
                                    },
                                    steps = songCacheValues.size - 2,
                                    valueRange = 0f..(songCacheValues.size - 1).toFloat()
                                )
                                LinearProgressIndicator(
                                    progress = { playerCacheProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    strokeCap = StrokeCap.Round
                                )
                                Spacer(modifier = Modifier.padding(2.dp))
                                Text(
                                    text = if (maxSongCacheSize == -1) {
                                        formatFileSize(playerCacheSize)
                                    } else {
                                        "${formatFileSize(playerCacheSize)} / ${
                                            formatFileSize(
                                                maxSongCacheSize * 1024 * 1024L
                                            )
                                        }"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_song_cache)) },
                        onClick = {
                            clearCacheDialog = true
                        }
                    )
                )
            )

            Material3SettingsGroup(
                title = stringResource(R.string.image_cache),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.manage_search),
                        title = { Text(stringResource(R.string.max_image_cache_size)) },
                        description = {
                            val imageCacheValues =
                                remember { listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192) }
                            Column {
                                Text(
                                    text = when (maxImageCacheSize) {
                                        0 -> stringResource(R.string.disable)
                                        else -> formatFileSize(maxImageCacheSize * 1024 * 1024L)
                                    }
                                )
                                Slider(
                                    value = imageCacheValues.indexOf(maxImageCacheSize).toFloat(),
                                    onValueChange = {
                                        val newValue = imageCacheValues[it.roundToInt()]
                                        val newLimitInBytes = newValue * 1024 * 1024L

                                        if (newLimitInBytes < imageCacheSize) {
                                            cacheUsage = imageCacheSize
                                            cacheType = imageCacheString
                                            onConfirmAction = { onMaxImageCacheSizeChange(newValue) }
                                            showCacheWarningDialog = true
                                        } else {
                                            onMaxImageCacheSizeChange(newValue)
                                        }
                                    },
                                    steps = imageCacheValues.size - 2,
                                    valueRange = 0f..(imageCacheValues.size - 1).toFloat()
                                )
                                LinearProgressIndicator(
                                    progress = { imageCacheProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    strokeCap = StrokeCap.Round
                                )
                                Spacer(modifier = Modifier.padding(2.dp))
                                Text(
                                    text = "${formatFileSize(imageCacheSize)} / ${
                                        formatFileSize(
                                            maxImageCacheSize * 1024 * 1024L
                                        )
                                    }",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.clear_all),
                        title = { Text(stringResource(R.string.clear_image_cache)) },
                        onClick = {
                            clearImageCacheDialog = true
                        }
                    )
                )
            )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.storage)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}
