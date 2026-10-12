package com.auramusic.innertube

import com.auramusic.innertube.models.YouTubeSearchResultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live check that a channel page still returns real header data and a video grid.
 *
 * YouTube reshapes this payload often, and a parser that throws on one unexpected
 * element used to blank the whole page - the channel screen then rendered placeholder
 * art with no name, no avatar and no videos. This test fails loudly the next time that
 * happens instead of leaving it to be noticed on a device.
 */
class ChannelPageLiveTest {

    /** Resolved from a live search so the id is real - a guessed id answers "channel does not exist". */
    private suspend fun realChannelId(): String =
        YouTube.youtubeSearch("markiplier").getOrNull()?.items
            ?.filterIsInstance<YouTubeSearchResultItem.Channel>()
            ?.firstOrNull()?.channelId
            ?: error("live search returned no channel to probe")

    @Test
    fun channelPageCarriesHeaderAndVideos(): Unit = runBlocking {
        val channelId = realChannelId()
        val page = YouTube.youtubeChannel(channelId).getOrThrow()
        println("[channel] id=$channelId name='${page.title}' avatar=${page.avatarUrl != null} " +
            "banner=${page.bannerUrl != null} subs=${page.subscriberCountText} videos=${page.videos.size}")
        page.videos.take(3).forEach { println("[channel]   ${it.videoId} thumbs=${it.thumbnails.size} ${it.title.take(40)}") }

        assertTrue("channel name was blank", page.title.isNotBlank())
        assertTrue("channel avatar missing", !page.avatarUrl.isNullOrBlank())
        assertTrue("no videos parsed", page.videos.isNotEmpty())
        assertTrue(
            "video rows carried no thumbnail",
            page.videos.any { it.thumbnails.isNotEmpty() },
        )
    }
}