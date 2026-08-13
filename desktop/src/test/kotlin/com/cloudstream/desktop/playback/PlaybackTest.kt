package com.cloudstream.desktop.playback

import com.cloudstream.desktop.ui.screens.buildPlayerHtml
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackTest {
    @Test
    fun `movie and episode responses become selectable media`() = runBlocking {
        val provider = ResolvingProvider()
        val movie = provider.newMovieLoadResponse(
            name = "Movie",
            url = "https://example.com/movie",
            type = TvType.Movie,
            dataUrl = "movie-data",
        )
        assertEquals(listOf(PlayableMedia("Play movie", "movie-data")), movie.playableMedia())

        val episodes = listOf(
            provider.newEpisode("episode-2") { season = 2; episode = 1; name = "Later" },
            provider.newEpisode("episode-1") { season = 1; episode = 2; name = "Pilot" },
        )
        val series = provider.newTvSeriesLoadResponse(
            name = "Series",
            url = "https://example.com/series",
            type = TvType.TvSeries,
            episodes = episodes,
        )

        assertEquals(
            listOf("S01E02 · Pilot", "S02E01 · Later"),
            series.playableMedia().map(PlayableMedia::title),
        )
    }

    @Test
    fun `link resolution filters unsupported links deduplicates and sorts quality`() = runBlocking {
        val result = resolvePlaybackSources(ResolvingProvider(), "episode-data")

        assertEquals(listOf(1080, 720), result.sources.map(PlaybackSource::quality))
        assertEquals(ExtractorLinkType.VIDEO, result.sources.first().type)
        assertTrue(result.sources.first().requiresCustomHeaders)
        assertEquals(null, result.error)
    }

    @Test
    fun `unimplemented metadata provider returns an actionable error`() = runBlocking {
        val provider = object : MainAPI() {
            override var name: String = "Metadata"
        }

        val result = resolvePlaybackSources(provider, "metadata")
        assertTrue(result.sources.isEmpty())
        assertNotNull(result.error)
        assertTrue(result.error.contains("does not supply streams"))
    }

    @Test
    fun `player html escapes untrusted stream urls`() {
        val maliciousUrl = "https://example.com/video.mp4?x=</script><script>alert(1)</script>&q=\""
        val html = buildPlayerHtml(
            PlaybackSource(
                source = "Test",
                name = "Test",
                url = maliciousUrl,
                quality = 1080,
                type = ExtractorLinkType.VIDEO,
                referer = "",
                headers = emptyMap(),
            ),
        )

        assertFalse(html.contains(maliciousUrl))
        assertTrue(html.contains("\\u003C/script\\u003E\\u003Cscript\\u003E"))
        assertTrue(html.contains("video/mp4"))
    }

    private class ResolvingProvider : MainAPI() {
        override var name: String = "Test"

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            callback(
                newExtractorLink("Test", "720p", "https://example.com/video.m3u8", ExtractorLinkType.M3U8) {
                    quality = 720
                },
            )
            callback(
                newExtractorLink("Test", "1080p", "https://example.com/video.mp4", ExtractorLinkType.VIDEO) {
                    quality = 1080
                    referer = "https://example.com/"
                    headers = mapOf("Authorization" to "token")
                },
            )
            // Duplicate and unsupported results must not leak into player choices.
            callback(
                newExtractorLink("Test", "1080p duplicate", "https://example.com/video.mp4", ExtractorLinkType.VIDEO) {
                    quality = 1080
                    referer = "https://example.com/"
                    headers = mapOf("Authorization" to "token")
                },
            )
            callback(newExtractorLink("Test", "Torrent", "magnet:?xt=test", ExtractorLinkType.MAGNET))
            return true
        }
    }
}
