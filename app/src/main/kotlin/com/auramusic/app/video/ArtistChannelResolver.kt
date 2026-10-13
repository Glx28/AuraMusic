/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.video

import com.auramusic.innertube.YouTube
import com.auramusic.innertube.models.YouTubeSearchResultItem

/**
 * Finds the channel a music artist should be linked to.
 *
 * An artist's channel id is often an auto-generated "- Topic" channel rather than the
 * one a person actually runs, so following it lands on an auto-generated page instead of
 * the artist's. The two describe the same artist and carry the same subscriber count,
 * which is what this matches on: when the id on hand is a generated one, the artist is
 * looked up by name and the channel whose subscriber count agrees with the artist's is
 * taken as the real one.
 */
object ArtistChannelResolver {

    /** Auto-generated music channels are named like this. */
    private const val TOPIC_SUFFIX = " - Topic"

    /** Subscriber counts drift as people join, so "same" means within this margin. */
    private const val SUBSCRIBER_TOLERANCE = 0.02

    data class Resolved(
        val channelId: String,
        val channelName: String,
        /** True when the channel we were given was a generated one and had to be replaced. */
        val replacedGenerated: Boolean,
    )

    /**
     * The channel to open for [artistName].
     *
     * [artistChannelId] and [artistSubscribersText] come from the artist page; both may be
     * absent, in which case only the name is used to look the artist up.
     */
    suspend fun resolve(
        artistName: String,
        artistChannelId: String?,
        artistSubscribersText: String?,
    ): Resolved? {
        val targetSubs = artistSubscribersText?.let { parseSubscribers(it) }

        // A real channel with a matching subscriber count needs no guessing.
        artistChannelId?.let { id ->
            val page = YouTube.youtubeChannel(id).getOrNull()
            val name = page?.title.orEmpty()
            val isGenerated = name.trim().endsWith(TOPIC_SUFFIX, ignoreCase = true)
            if (!isGenerated) {
                return Resolved(channelId = id, channelName = name, replacedGenerated = false)
            }
        }

        // Either there was no id, or the one there was generated: look the artist up and
        // take the channel whose audience matches the artist's.
        val candidates = YouTube.youtubeSearch(artistName).getOrNull()?.items
            ?.filterIsInstance<YouTubeSearchResultItem.Channel>()
            .orEmpty()
            .filterNot { it.title.trim().endsWith(TOPIC_SUFFIX, ignoreCase = true) }
            .filter { it.title.equals(artistName, ignoreCase = true) || similar(it.title, artistName) }

        if (candidates.isEmpty()) {
            // Nothing matched by name; the id we started with is better than a guess.
            return artistChannelId?.let { Resolved(it, artistName, replacedGenerated = false) }
        }

        if (targetSubs != null) {
            candidates
                .mapNotNull { candidate ->
                    val subs = candidate.subscriberCountText?.let { parseSubscribers(it) } ?: return@mapNotNull null
                    val drift = kotlin.math.abs(subs - targetSubs).toDouble() / targetSubs
                    candidate to drift
                }
                .filter { it.second <= SUBSCRIBER_TOLERANCE }
                .minByOrNull { it.second }
                ?.let { (candidate, _) ->
                    return Resolved(candidate.channelId, candidate.title, replacedGenerated = true)
                }
        }

        val best = candidates.first()
        return Resolved(best.channelId, best.title, replacedGenerated = true)
    }

    /**
     * Reads a subscriber count out of the shapes YouTube writes it in: "38.9M subscribers",
     * "1.2K", "@markiplier" (which carries no count at all).
     */
    fun parseSubscribers(text: String): Long? {
        val cleaned = text.trim()
        if (cleaned.startsWith("@")) return null
        val match = Regex("""([\d.,]+)\s*([KMB])?""", RegexOption.IGNORE_CASE).find(cleaned)
            ?: return null
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "K" -> 1_000
            "M" -> 1_000_000
            "B" -> 1_000_000_000
            else -> 1
        }
        return (number * multiplier).toLong()
    }

    /** Looser than equality, for names written differently on the two sides. */
    private fun similar(a: String, b: String): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        return left == right || left.contains(right) || right.contains(left)
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(TOPIC_SUFFIX.lowercase(), "")
            .replace(Regex("[^a-z0-9]+"), "")
            .trim()
}