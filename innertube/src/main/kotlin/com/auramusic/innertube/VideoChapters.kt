/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.innertube

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Chapters and scrub previews for a video.
 *
 * Both live in responses this app did not previously read. Chapters come with the watch
 * page, as markers alongside the heatmap, and only on videos that have them. Scrub
 * previews come from a storyboard specification in the player response - and only from
 * some clients, since the web player's response no longer carries it.
 */
object VideoChapters {

    data class Chapter(
        val title: String,
        val startMs: Long,
    )

    /**
     * Reads the chapter markers out of a watch-page response.
     *
     * Returns an empty list for the many videos that have no chapters, which is the
     * common case rather than an error: the heatmap markers are filtered out and only
     * titled markers - the ones a chapter list is made of - are kept.
     */
    fun parseChapters(root: JsonElement): List<Chapter> {
        val panels = root.asObject()?.get("engagementPanels") ?: return emptyList()
        val chapters = mutableListOf<Chapter>()

        fun walk(node: JsonElement?) {
            val obj = node?.asObject() ?: return
            // Chapter markers are titled; heatmap markers carry an intensity instead and
            // are what a chapter list is not.
            if (obj.containsKey("markerListItemRenderer")) {
                val marker = obj["markerListItemRenderer"]?.asObject()
                val title = marker?.get("title")?.asObject()
                    ?.let { textFrom(it) }
                    ?.takeIf { it.isNotBlank() }
                val start = marker?.get("startTimeMs")?.asObject()
                    ?.get("simpleText")?.asString()?.toLongOrNull()
                if (title != null && start != null) {
                    chapters += Chapter(title = title, startMs = start)
                }
            }
            obj.values.forEach { walk(it) }
        }

        walk(panels)
        return chapters.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    /**
     * The storyboard specification, which describes how to address a thumbnail for any
     * moment in the video.
     */
    fun parseStoryboardSpec(root: JsonElement): String? =
        root.asObject()
            ?.get("playerStoryboardSpecRenderer")?.asObject()
            ?.get("spec")?.asString()
            ?.takeIf { it.isNotBlank() }

    /**
     * Thumbnail URL for the moment at [timeMs].
     *
     * The specification is a URL template with numbered slots; a level and a tile index
     * are written into it. Level 1 is the small grid and level 2 the one large enough to
     * read while scrubbing, so the second is what this addresses.
     */
    fun storyboardUrl(
        spec: String,
        timeMs: Long,
        durationMs: Long,
        level: Int = 2,
    ): String? {
        if (spec.isBlank() || durationMs <= 0 || timeMs < 0) return null
        val url = spec
            // "L$2$" / "M$1$" style slots become the tile indices, zero padded to two.
            .replace(Regex("""[LMS]\$(\d)\$""")) { match ->
                val slot = match.groupValues[1].toInt()
                val index = tileIndex(timeMs, durationMs, level, slot)
                "${match.value[0]}" + index.toString().padStart(2, '0')
            }
            // A bare "$0" is the same idea for single-digit formats.
            .replace(Regex("""\$(\d)(?!\$)""")) { match ->
                val slot = match.groupValues[1].toInt()
                tileIndex(timeMs, durationMs, level, slot).toString()
            }
        return url.takeIf { it.contains("://") }
    }

    /**
     * Which tile a moment falls in. Every level is a grid of `name` columns by
     * `name` rows of `name` x `name` frames; the frames are evenly spread across the
     * video, so a moment maps to the tile holding it.
     */
    private fun tileIndex(timeMs: Long, durationMs: Long, level: Int, slot: Int): Int {
        val name = 5.coerceAtMost(1 + level)      // 2..6 frames per tile edge
        val interval = durationMs / (name * name * name)
        if (interval <= 0) return 0
        val frame = (timeMs / interval).coerceAtLeast(0L)
        return when (slot) {
            0 -> frame.toInt()
            1 -> (frame / name).toInt()
            else -> (frame % name).toInt()
        }
    }

    /** The chapter containing [timeMs], or null when there are none. */
    fun chapterAt(chapters: List<Chapter>, timeMs: Long): Chapter? =
        chapters.lastOrNull { it.startMs <= timeMs }

    private fun textFrom(obj: JsonObject): String? =
        obj["simpleText"]?.asString()
            ?: obj["runs"]?.let { runs ->
                runs.asObject()?.get("text")?.asString()
            }
            ?: (obj["accessibility"]?.asObject()?.get("accessibilityData")?.asObject()
                ?.get("label")?.asString())

    private fun JsonElement?.asObject(): JsonObject? = runCatching { this?.jsonObject }.getOrNull()
    private fun JsonElement?.asString(): String? =
        runCatching { (this as? JsonPrimitive)?.content }.getOrNull()
}