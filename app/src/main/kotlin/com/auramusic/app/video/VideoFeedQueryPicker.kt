/*
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.video

import kotlin.random.Random

/**
 * Picks what the video feed asks YouTube for next.
 *
 * The feed used to be one fixed query with a continuation token, and YouTube now
 * answers a search continuation with an empty page - so the feed stopped growing and
 * showed the same twenty videos forever. Each page is therefore a fresh query instead,
 * which both keeps the list growing and lets it follow what the user actually watches.
 *
 * Choices lean on the watch history first (the channels and words of what has been
 * played), fall back to a rotating pool of music queries, and never repeat a query that
 * was used this session unless the pool is exhausted.
 */
object VideoFeedQueryPicker {

    /** Base pool: broad enough that every entry returns a full page of videos. */
    private val BASE_QUERIES = listOf(
        "music videos",
        "official music video",
        "new music releases",
        "popular songs official video",
        "live music performance",
        "album official video",
        "lyrics official video",
        "remix official video",
        "acoustic cover songs",
        "music video hits",
        "hip hop official video",
        "pop music official video",
        "rock band official video",
        "afrobeats official video",
        "latin music video",
        "r&b official video",
        "electronic music video",
        "indie band official video",
        "90s music videos",
        "2000s music videos",
    )

    /** Words that show up in watched titles and make a better query than the channel name. */
    private val STOP_WORDS = setOf(
        "official", "video", "music", "lyrics", "lyric", "video", "audio", "hd", "hq",
        "remastered", "remaster", "live", "explicit", "the", "a", "an", "and", "ft",
        "feat", "with", "mv", "m/v", "full", "album", "version", "mix",
    )

    private val recentQueries = ArrayDeque<String>()

    /**
     * Next query to run. [historyKeywords] are drawn from what the user watched, so the
     * feed drifts towards their listening rather than staying a fixed genre.
     */
    @Synchronized
    fun nextQuery(historyKeywords: List<String>): String {
        val personal = historyKeywords
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in STOP_WORDS }
            .distinct()
            .shuffled()
            .take(3)
            .map { "$it official video" }

        val pool = (personal + BASE_QUERIES.shuffled()).filter { it !in recentQueries }
        val chosen = pool.firstOrNull()
            ?: BASE_QUERIES.shuffled().first()  // everything used: start the rotation again
        recentQueries.addLast(chosen)
        while (recentQueries.size > BASE_QUERIES.size) recentQueries.removeFirst()
        return chosen
    }

    /**
     * Distinctive words from recently watched videos, used to steer the feed. Channel
     * names come along because for music that is usually the artist.
     */
    fun keywordsFrom(
        titles: List<String>,
        channels: List<String>,
        limit: Int = 12,
    ): List<String> {
        val words = (channels + titles)
            .asSequence()
            .flatMap { it.split(' ', '\n', '(', ')', '[', ']', '|', '-', ',', '!', '?') }
            .map { it.trim() }
            .filter { it.length >= 3 && it.all { ch -> ch.isLetterOrDigit() } }
            .filter { it.lowercase() !in STOP_WORDS }
            .toList()

        // Keep the words that show up across several watched videos: those are the
        // listening habits, not one-off title noise.
        return words.groupingBy { it.lowercase() }.eachCount()
            .filterValues { it > 1 }
            .keys
            .shuffled()
            .take(limit)
            .map { it.replaceFirstChar(Char::uppercase) }
            .ifEmpty { words.shuffled().take(limit) }
    }

    /** Used by tests and by the "new feed" action to force variety. */
    fun reset() = synchronized(this) { recentQueries.clear() }
}