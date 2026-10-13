/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.di

import com.auramusic.app.video.VideoDownloadStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Reaches the download store from code that has no injected dependencies of its own.
 *
 * Playback asks whether a video is on the device before it resolves a stream, which
 * happens deep inside a player that is not itself injected, so the store has to be
 * reachable from a context alone.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VideoDownloadStoreEntryPoint {
    fun videoDownloadStore(): VideoDownloadStore
}
