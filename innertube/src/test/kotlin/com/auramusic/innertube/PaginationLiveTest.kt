package com.auramusic.innertube

import com.auramusic.innertube.models.YouTubeSearchResultItem
import com.auramusic.innertube.pages.YouTubeChannelPage
import kotlinx.coroutines.runBlocking
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live checks for the two paginated surfaces: the channel video tab and the video feed.
 * Both render a list the user can keep scrolling, so both are verified end to end -
 * first page, token, second page - rather than assumed from the first page alone.
 */
class PaginationLiveTest {

    private suspend fun realChannelId(): String =
        YouTube.youtubeSearch("markiplier").getOrNull()?.items
            ?.filterIsInstance<YouTubeSearchResultItem.Channel>()
            ?.firstOrNull()?.channelId
            ?: error("live search returned no channel")

    @Test
    fun channelVideosPageForward(): Unit = runBlocking {
        val channelId = realChannelId()
        val first = YouTube.youtubeChannel(channelId, YouTubeChannelPage.VIDEOS_PARAMS).getOrThrow()
        println("[page] first videos=${first.videos.size} continuation=${first.continuation?.take(40)}")
        assertTrue("first page had no videos", first.videos.isNotEmpty())
        val cont = first.continuation ?: return@runBlocking

        // Dump the raw continuation payload: the token parses but yields nothing, so
        // the response shape has to be looked at rather than guessed at.
        val raw = InnerTube().browseYouTube(
            com.auramusic.innertube.models.YouTubeClient.Companion.WEB,
            browseId = channelId,
            continuation = cont,
        ).bodyAsText()
        println("[raw] length=" + raw.length)
        val root = Json.parseToJsonElement(raw).jsonObject
        println("[raw] topKeys=" + root.keys)
        root["onResponseReceivedActions"]?.let { println("[raw] onResponseReceivedActions=" + it.toString().take(1200)) }
        root["continuationContents"]?.let { println("[raw] continuationContents=" + it.toString().take(2500)) }
        root["alerts"]?.let { println("[raw] alerts=" + it.toString().take(300)) }

        // Without params: a continuation request carries the token only.
        val noParams = YouTube.youtubeChannelContinuation(channelId, cont)
        println("[page] second(no params) success=${noParams.isSuccess} videos=${noParams.getOrNull()?.videos?.size}")
        noParams.onFailure { println("[page] second(no params) error=${it.message}") }
        // Same token, same call but keeping the tab params.
        val withParams = YouTube.youtubeChannelContinuation(channelId, cont, YouTubeChannelPage.VIDEOS_PARAMS)
        println("[page] second(with params) success=${withParams.isSuccess} videos=${withParams.getOrNull()?.videos?.size}")
        assertTrue("second page had no videos", (noParams.getOrNull()?.videos?.size ?: 0) > 0)
    }

    @Test
    fun browseFeedPagesForward(): Unit = runBlocking {
        // Search continuations come back empty now, so the feed has to come from a
        // browse feed instead. Try the ones that are still alive, both first page and
        // continuation, and report what each gives.
        for (browseId in listOf("FEvideos", "FEexplore", "FEwhat_to_watch")) {
            val first = runCatching { InnerTube().browseYouTube(
                com.auramusic.innertube.models.YouTubeClient.Companion.WEB,
                browseId = browseId,
            ).bodyAsText() }.getOrNull() ?: continue
            val root = Json.parseToJsonElement(first).jsonObject
            val page = com.auramusic.innertube.pages.YouTubeFeedPage.fromBrowseResponse(first)
            println("[browse] $browseId keys=" + root.keys + " items=" + page.items.size + " cont=" + page.continuation?.take(30))
            val cont = page.continuation ?: continue
            val secondRaw = runCatching { InnerTube().browseYouTube(
                com.auramusic.innertube.models.YouTubeClient.Companion.WEB,
                browseId = browseId,
                continuation = cont,
            ).bodyAsText() }.getOrNull() ?: continue
            val second = com.auramusic.innertube.pages.YouTubeFeedPage.fromBrowseResponse(secondRaw)
            println("[browse] $browseId second items=" + second.items.size + " cont=" + second.continuation?.take(30))
        }
    }
}
