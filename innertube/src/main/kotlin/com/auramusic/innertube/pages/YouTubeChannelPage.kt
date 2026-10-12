package com.auramusic.innertube.pages

import com.auramusic.innertube.models.Thumbnail
import com.auramusic.innertube.models.YouTubeVideoItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A regular YouTube channel's header info and one video tab, parsed from the
 * WEB /youtubei/v1/browse response (browseId = channelId, params = tab params).
 */
data class YouTubeChannelPage(
    val channelId: String,
    val title: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val subscriberCountText: String?,
    val videosCountText: String?,
    val description: String?,
    val videos: List<YouTubeVideoItem>,
    val continuation: String? = null,
    val isSubscribed: Boolean? = null,
) {
    companion object {
        // Tab params from youtube.com channel tabs.
        const val VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
        const val SHORTS_PARAMS = "EgZzaG9ydHPyBgUKA5oBAA%3D%3D"
        const val LIVE_PARAMS = "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"

        /**
         * YouTube reshapes these payloads without warning, and a single unexpected
         * element used to throw out of the whole parse - which is what left the
         * channel screen rendering an empty shell with placeholder art. Every optional
         * read below now goes through these helpers instead of casting directly.
         */
        private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

        private fun JsonElement?.arr(): List<JsonElement> = (this as? kotlinx.serialization.json.JsonArray).orEmpty()

        private fun JsonElement?.str(): String? = runCatching {
            (this as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.getOrNull()

        /** First string value stored under [key] anywhere in this subtree. */
        private fun JsonElement?.deepString(key: String, depth: Int = 0): String? {
            if (depth > 8) return null
            val obj = this?.obj() ?: return null
            obj[key]?.str()?.takeIf { it.isNotBlank() }?.let { return it }
            obj.values.forEach { child ->
                child.deepString(key, depth + 1)?.let { return it }
            }
            return null
        }

        /** First object stored under [key] anywhere in this subtree. */
        private fun JsonElement?.deepObject(key: String, depth: Int = 0): JsonObject? {
            if (depth > 8) return null
            val obj = this?.obj() ?: return null
            (obj[key] as? JsonObject)?.let { return it }
            obj.values.forEach { child ->
                child.deepObject(key, depth + 1)?.let { return it }
            }
            return null
        }

        /**
         * First array stored under [key] anywhere in this subtree. Image lists live under
         * "sources" and "thumbnails", both of which are arrays - searching only for
         * objects is why avatars came back empty while the banner (which has a separate
         * reader) worked.
         */
        private fun JsonElement?.deepArray(key: String, depth: Int = 0): List<JsonElement>? {
            if (depth > 8) return null
            val obj = this?.obj() ?: return null
            (obj[key] as? kotlinx.serialization.json.JsonArray)?.let { return it.toList() }
            obj.values.forEach { child ->
                child.deepArray(key, depth + 1)?.let { return it }
            }
            return null
        }

        /** Largest image URL under any "sources"/"thumbnails" list in this subtree. */
        private fun JsonElement?.largestImageUrl(): String? {
            val list = deepArray("sources") ?: deepArray("thumbnails") ?: return null
            return list
                .mapNotNull { src -> src.obj()?.get("url")?.str() }
                .lastOrNull()
        }

        fun fromJson(channelId: String, element: JsonElement): YouTubeChannelPage {
            val root = element.jsonObject
            val header = root["header"]?.jsonObject
            val metadata = root["metadata"]?.jsonObject
                ?.get("channelMetadataRenderer")?.jsonObject
            val microformat = root["microformat"]?.jsonObject
                ?.get("microformatDataRenderer")?.jsonObject

            // Header can be pageHeaderRenderer (2024+) or c4TabbedHeaderRenderer (legacy).
            var title: String? = null
            var avatarUrl: String? = null
            var bannerUrl: String? = null
            var subscriberCountText: String? = null
            var videosCountText: String? = null
            var isSubscribed: Boolean? = null

            header?.get("pageHeaderRenderer")?.obj()?.let { pageHeader ->
                val content = pageHeader["content"]?.obj()
                    ?.get("pageHeaderViewModel")?.obj()
                // Title lives at pageHeaderViewModel.title.dynamicTextViewModel.text.content
                // in 2024+ channel headers. Extracted here so the metadata fallback below
                // is only used for legacy responses that don't have pageHeaderRenderer.
                title = content?.get("title")?.obj()
                    ?.get("dynamicTextViewModel")?.obj()
                    ?.get("text")?.obj()
                    ?.get("content")?.str()
                    ?: content?.deepString("pageTitle")
                val metadataRows = content?.get("metadata")?.obj()
                    ?.get("contentMetadataViewModel")?.obj()
                    ?.get("metadataRows").arr()
                metadataRows.forEach { row ->
                    val parts = row.obj()?.get("metadataParts").arr()
                    parts.forEach { part ->
                        val text = part.obj()?.get("text")?.obj()?.get("content")?.str()
                        if (text != null) {
                            when {
                                text.contains("subscriber", ignoreCase = true) ->
                                    subscriberCountText = text
                                text.contains("video", ignoreCase = true) ->
                                    videosCountText = text
                            }
                        }
                    }
                }
                // Same text, different nesting: the row parts live under
                // metadataParts[].text.content in some builds and contentMetadataViewModel
                // elsewhere, so the counts are also picked up by name.
                if (subscriberCountText == null) {
                    content?.deepString("content")?.takeIf { it.contains("subscriber", true) }
                        ?.let { subscriberCountText = it }
                }
                avatarUrl = content?.get("image")?.obj()
                    ?.get("decoratedAvatarViewModel")?.obj()
                    ?.get("avatar")?.obj()
                    ?.get("avatarViewModel")?.obj()
                    ?.get("image")?.largestImageUrl()
                    ?: content?.get("image")?.largestImageUrl()
                // 2025+ headers carry the banner inside the same view model; reading it
                // here is what stops the header from rendering a flat grey block.
                bannerUrl = content?.get("banner")?.largestImageUrl()
                // The subscription toggle moved again: it used to be an array of
                // contentActionButtonViewModel, now it is flexibleActionsViewModel with
                // rows of actions. Search the whole actions subtree instead of assuming
                // one of the two shapes - and never throw if neither matches.
                val actions = content?.get("actions")
                isSubscribed = actions.deepString("isToggled")?.takeIf { it == "true" || it == "false" }
                    ?.let { it == "true" }
                    ?: actions.deepString("isSubscribed")?.takeIf { it == "true" || it == "false" }
                        ?.let { it == "true" }
            }
            header?.get("c4TabbedHeaderRenderer")?.obj()?.let { c4 ->
                title = c4["title"]?.obj()?.get("simpleText")?.str()
                avatarUrl = c4["avatar"]?.largestImageUrl()
                bannerUrl = c4["banner"]?.largestImageUrl()
                subscriberCountText = c4["subscriberCountText"]?.obj()?.get("simpleText")?.str()
                videosCountText = c4["videosCountText"]?.obj()
                    ?.get("runs").arr()
                    ?.firstNotNullOfOrNull { it.obj()?.get("text")?.str() }
                // Legacy headers carry the subscribe state on the subscribe button.
                if (isSubscribed == null) {
                    isSubscribed = c4["subscribeButton"]?.obj()
                        ?.get("subscribeButtonRenderer")?.obj()
                        ?.get("subscribed")?.str()
                        ?.let { it == "true" }
                }
            }

            metadata?.let { meta ->
                if (title == null) {
                    title = meta["title"]?.str()
                }
                if (avatarUrl == null) {
                    avatarUrl = meta["avatar"]?.largestImageUrl()
                }
            }
            microformat?.let { micro ->
                if (thumbnailUrlHelper(micro) != null && bannerUrl == null) {
                    bannerUrl = thumbnailUrlHelper(micro)
                }
            }

            val description = metadata?.get("description")?.let { desc ->
                desc.str()
                    ?: desc.obj()?.get("runs").arr()
                        ?.joinToString("") { it.obj()?.get("text")?.str().orEmpty() }
                        ?.takeIf { it.isNotBlank() }
            }

            // Videos live under either tabRenderer or richGridRenderer.
            val videos = mutableListOf<YouTubeVideoItem>()
            var continuation: String? = null
            root["contents"]?.obj()?.get("twoColumnBrowseResultsRenderer")?.obj()
                ?.get("tabs").arr().forEach { tabEl ->
                    val tabContent = tabEl.obj()?.get("tabRenderer")?.obj()
                        ?.get("content")?.obj() ?: return@forEach
                    // Videos/Live use sectionListRenderer; Shorts uses richGridRenderer.
                    val sections: List<JsonElement> =
                        tabContent["sectionListRenderer"]?.obj()
                            ?.get("contents").arr()
                            .ifEmpty {
                                tabContent["richGridRenderer"]?.obj()?.get("contents").arr()
                            }
                    sections.forEach { sectionEl ->
                        val itemSectionContents = sectionEl.obj()?.get("itemSectionRenderer")
                            ?.obj()?.get("contents").arr()
                        val items: List<JsonElement> = when {
                            itemSectionContents.isNotEmpty() -> itemSectionContents
                            sectionEl.obj()?.get("richItemRenderer") != null -> listOf(sectionEl)
                            else -> emptyList()
                        }
                        items.forEach { itemEl ->
                            val richContent = itemEl.obj()?.get("richItemRenderer")
                                ?.obj()?.get("content")?.obj()
                            val directVideoRenderer = itemEl.obj()?.get("videoRenderer")?.obj()
                            val item = when {
                                directVideoRenderer != null -> {
                                    parseVideoRenderer(directVideoRenderer)?.let(videos::add)
                                    null
                                }
                                richContent != null -> richContent
                                else -> itemEl.obj()
                            } ?: return@forEach
                            // richItemRenderer content may be a videoRenderer, reelItemRenderer
                            // (Shorts) or the newer lockupViewModel.
                            val rendered = item["videoRenderer"]?.obj()
                                ?: item["reelItemRenderer"]?.obj()
                            if (rendered != null) {
                                parseVideoRenderer(rendered)?.let(videos::add)
                            } else {
                                item["lockupViewModel"]?.let { raw ->
                                    runCatching { Lockup.parse(raw) }
                                        ?.getOrNull()
                                        ?.let(Lockup::toVideoItem)
                                        ?.let(videos::add)
                                }
                            }
                        }
                        // Continuation token sits either in the section or the grid.
                        collectContinuation(sectionEl)?.let { continuation = it }
                    }
                }

            return YouTubeChannelPage(
                channelId = channelId,
                title = title.orEmpty(),
                avatarUrl = avatarUrl,
                bannerUrl = bannerUrl,
                subscriberCountText = subscriberCountText,
                videosCountText = videosCountText,
                description = description,
                videos = videos,
                continuation = continuation,
                isSubscribed = isSubscribed,
            )
        }

        /**
         * Parses a `videoRenderer`. `playlistVideoRenderer` uses the same field layout
         * (videoId / title / byline / lengthText / thumbnail), so the playlist page
         * reuses this rather than duplicating it.
         */
        internal fun parseVideoRenderer(renderer: JsonObject): YouTubeVideoItem? {
            val videoId = renderer["videoId"]?.jsonPrimitive?.content ?: return null
            val title = renderer["title"]?.jsonObject?.let { textOf(it) } ?: return null
            val byline = renderer["longBylineText"]?.jsonObject
                ?: renderer["shortBylineText"]?.jsonObject
            val channelName = byline?.let { textOf(it) }.orEmpty()
            val channelId = byline?.get("runs")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("navigationEndpoint")?.jsonObject
                ?.get("browseEndpoint")?.jsonObject
                ?.get("browseId")?.jsonPrimitive?.content
            val viewCount = renderer["viewCountText"]?.jsonObject?.let { textOf(it) }
            val published = renderer["publishedTimeText"]?.jsonObject?.let { textOf(it) }
            val lengthText = renderer["lengthText"]?.jsonObject?.let { textOf(it) }
            val thumbnails = renderer["thumbnail"]?.jsonObject?.get("thumbnails")?.jsonArray
                ?.mapNotNull { thumb ->
                    val url = thumb.jsonObject["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    Thumbnail(
                        url = url,
                        width = thumb.jsonObject["width"]?.jsonPrimitive?.content?.toIntOrNull(),
                        height = thumb.jsonObject["height"]?.jsonPrimitive?.content?.toIntOrNull(),
                    )
                }.orEmpty()
            val isLive = renderer["badges"]?.jsonArray?.any { badge ->
                badge.jsonObject["metadataBadgeRenderer"]?.jsonObject
                    ?.get("style")?.jsonPrimitive?.content == "BADGE_STYLE_TYPE_LIVE"
            } == true || viewCount?.contains("watching", ignoreCase = true) == true
            return YouTubeVideoItem(
                videoId = videoId,
                title = title,
                channelName = channelName,
                channelId = channelId,
                viewCountText = viewCount,
                publishedTimeText = published,
                durationText = lengthText,
                thumbnails = thumbnails,
                isLive = isLive,
            )
        }

        private fun textOf(obj: JsonObject): String? {
            obj["simpleText"]?.jsonPrimitive?.content?.let { return it }
            return obj["runs"]?.jsonArray
                ?.joinToString("") { run ->
                    run.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                }?.takeIf { it.isNotBlank() }
        }

        private fun thumbnailUrlHelper(obj: JsonObject): String? =
            obj["thumbnail"]?.jsonObject?.get("thumbnails")?.jsonArray
                ?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content

        private fun collectContinuation(element: JsonElement): String? {
            val direct = element.jsonObject["continuationItemRenderer"]?.jsonObject
                ?: element.jsonObject["itemSectionRenderer"]?.jsonObject?.get("contents")?.jsonArray
                    ?.firstOrNull { it.jsonObject["continuationItemRenderer"] != null }
                    ?.jsonObject?.get("continuationItemRenderer")?.jsonObject
                ?: return null
            return direct["continuationEndpoint"]?.jsonObject
                ?.get("continuationCommand")?.jsonObject
                ?.get("token")?.jsonPrimitive?.content
        }
    }
}
