package com.auramusic.innertube

import com.auramusic.innertube.models.YouTubeClient.Companion.WEB
import com.auramusic.innertube.models.YouTubeSearchResultItem
import com.auramusic.innertube.pages.YouTubeChannelPage
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Live probe: how does YouTube actually group a channel's videos today? */
class ChannelLayoutProbeTest {

    @Test
    fun probeShelvesAndTabs(): Unit = runBlocking {
        val channelId = YouTube.youtubeSearch("markiplier").getOrNull()?.items
            ?.filterIsInstance<YouTubeSearchResultItem.Channel>()?.firstOrNull()?.channelId
            ?: error("no channel")
        val body = InnerTube().browseYouTube(WEB, browseId = channelId, params = YouTubeChannelPage.VIDEOS_PARAMS)
            .bodyAsText()
        val root = Json.parseToJsonElement(body).jsonObject
        val contents = root["contents"]?.toString().orEmpty()
        listOf("shelfRenderer", "richShelfRenderer", "playlistRenderer", "lockupViewModel", "videoRenderer",
               "reelItemRenderer", "gridVideoRenderer", "continuationItemRenderer")
            .forEach { key ->
                println("[layout] $key occurrences=" + Regex(key).findAll(contents).count())
            }
        // Section titles, if the channel groups its videos into shelves.
        Regex("""\"title\":\{\"simpleText\":\"([^\"]{1,60})\"\}""").findAll(contents)
            .map { it.groupValues[1] }
            .distinct()
            .take(20)
            .forEach { println("[layout] section title: " + it) }
        // Sort options offered on the Videos tab.
        Regex("""\"contentMetadataViewModel\":\{"category\":\"([A-Z_]+)\"""")
            .findAll(contents).map { it.groupValues[1] }.distinct()
            .forEach { println("[layout] sort category: " + it) }
    }
}
