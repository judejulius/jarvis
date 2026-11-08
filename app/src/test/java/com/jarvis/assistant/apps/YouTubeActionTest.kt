package com.jarvis.assistant.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeActionTest {
    private fun item(id: Int, label: String, clickable: Boolean = true) = ScreenItem(id, label, clickable)

    @Test fun picksTheFirstOrganicVideo() {
        val items = listOf(
            item(1, "Search"),
            item(2, "Despacito Official - 4 minutes, 41 seconds - Go to channel - Luis Fonsi - 8B views - 7 years ago - play video"),
            item(3, "Despacito Remix - 3 minutes, 10 seconds - Go to channel - DJ - 1M views - 2 years ago - play video"),
        )
        assertEquals(2, YouTubeAction.pickResult(items)?.id)
        assertEquals("Despacito Official", YouTubeAction.titleOf(items[1]))
    }

    @Test fun skipsAdsPlaylistsAndShortsShelves() {
        val items = listOf(
            item(1, "Buy Shoes Now - Sponsored - Brand - 30 seconds - play video"),
            item(2, "Best songs - Playlist - 50 videos - Channel"),
            item(3, "Shorts - funny clips - 1 minute - play video"),
            item(4, "Real Video - 5 minutes, 2 seconds - Go to channel - Creator - 100K views - 1 month ago - play video"),
        )
        assertEquals(4, YouTubeAction.pickResult(items)?.id)
    }

    @Test fun aSongTitleContainingMixIsStillAResult() {
        val items = listOf(item(1, "Summer Mix 2024 - 3 minutes, 1 second - Go to channel - DJ Sam - 2M views - 1 year ago - play video"))
        assertEquals(1, YouTubeAction.pickResult(items)?.id)
    }

    @Test fun nothingIsTappedWhenTheScreenDoesNotShowResults() {
        assertNull(YouTubeAction.pickResult(emptyList()))
        assertNull(YouTubeAction.pickResult(listOf(item(1, "Search"), item(2, "Home"), item(3, "Subscriptions"))))
        assertNull(YouTubeAction.pickResult(listOf(item(1, "Video - 3 minutes, 1 second - Creator - play video", clickable = false))))
    }

    @Test fun queryCleaning() {
        assertEquals("despacito", YouTubeAction.cleanQuery("despacito song"))
        assertEquals("lofi music", YouTubeAction.cleanQuery("lofi music"))
        assertEquals("kesariya", YouTubeAction.cleanQuery("kesariya gaana"))
    }
}
