package com.cloudstream.desktop.playback

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.Collections

private const val DEFAULT_LINK_TIMEOUT_MS = 120_000L

/** A provider-specific value that can be passed to MainAPI.loadLinks. */
data class PlayableMedia(
    val title: String,
    val data: String,
)

/** Everything the desktop player needs from an extracted CloudStream link. */
data class PlaybackSource(
    val source: String,
    val name: String,
    val url: String,
    val quality: Int,
    val type: ExtractorLinkType,
    val referer: String,
    val headers: Map<String, String>,
) {
    val displayName: String
        get() = buildString {
            append(name.ifBlank { source.ifBlank { "Stream" } })
            if (quality > 0) append(" · ${quality}p")
            append(" · ${type.name}")
        }

    val requiresCustomHeaders: Boolean
        get() = referer.isNotBlank() || headers.isNotEmpty()

    companion object {
        fun from(link: ExtractorLink): PlaybackSource = PlaybackSource(
            source = link.source,
            name = link.name,
            url = link.url,
            quality = link.quality,
            type = link.type,
            referer = link.referer,
            headers = link.headers,
        )
    }
}

data class LinkResolutionResult(
    val sources: List<PlaybackSource>,
    val subtitles: List<SubtitleFile>,
    val error: String? = null,
)

/**
 * Converts every currently supported LoadResponse shape into selectable movies or episodes.
 * Torrent responses are intentionally omitted because the embedded player cannot play them.
 */
fun LoadResponse.playableMedia(): List<PlayableMedia> = when (this) {
    is MovieLoadResponse -> dataUrl.takeIf(String::isNotBlank)?.let {
        listOf(PlayableMedia("Play movie", it))
    }.orEmpty()

    is LiveStreamLoadResponse -> dataUrl.takeIf(String::isNotBlank)?.let {
        listOf(PlayableMedia("Play live stream", it))
    }.orEmpty()

    is TvSeriesLoadResponse -> episodes
        .sortedWith(episodeComparator)
        .map { episode -> PlayableMedia(episode.displayTitle(), episode.data) }

    is AnimeLoadResponse -> episodes.entries
        .sortedBy { it.key.name }
        .flatMap { (dubStatus, episodes) ->
            episodes.sortedWith(episodeComparator).map { episode ->
                PlayableMedia("${dubStatus.name} · ${episode.displayTitle()}", episode.data)
            }
        }

    else -> emptyList()
}

/** Resolve links without letting one faulty extension crash the Compose UI coroutine. */
suspend fun resolvePlaybackSources(
    provider: MainAPI,
    data: String,
): LinkResolutionResult {
    if (data.isBlank()) {
        return LinkResolutionResult(emptyList(), emptyList(), "The provider returned empty playback data.")
    }

    val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
    val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())
    val timeout = provider.loadLinksTimeoutMs?.coerceAtLeast(1L) ?: DEFAULT_LINK_TIMEOUT_MS

    fun linkSnapshot(): List<ExtractorLink> = synchronized(links) { links.toList() }
    fun subtitleSnapshot(): List<SubtitleFile> = synchronized(subtitles) { subtitles.toList() }

    return try {
        val completed = withTimeout(timeout) {
            provider.loadLinks(
                data = data,
                isCasting = false,
                subtitleCallback = { subtitles += it },
                callback = { links += it },
            )
        }

        val sources = linkSnapshot()
            .asSequence()
            .filter { it.url.isNotBlank() }
            .filter { it.type != ExtractorLinkType.TORRENT && it.type != ExtractorLinkType.MAGNET }
            .distinctBy { listOf(it.url, it.type, it.referer, it.headers) }
            .sortedByDescending(ExtractorLink::quality)
            .map(PlaybackSource::from)
            .toList()

        val error = when {
            sources.isNotEmpty() -> null
            !completed -> "The provider could not resolve this item."
            else -> "The provider completed without returning a playable stream."
        }
        LinkResolutionResult(sources, subtitleSnapshot(), error)
    } catch (_: TimeoutCancellationException) {
        LinkResolutionResult(
            emptyList(),
            subtitleSnapshot(),
            "Link extraction timed out after ${timeout / 1_000}s.",
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: NotImplementedError) {
        LinkResolutionResult(
            emptyList(),
            subtitleSnapshot(),
            "This metadata provider does not supply streams. Install or select a streaming extension.",
        )
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        LinkResolutionResult(
            emptyList(),
            subtitleSnapshot(),
            error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName ?: "Link extraction failed.",
        )
    }
}

private val episodeComparator = compareBy<Episode>(
    { it.season ?: Int.MAX_VALUE },
    { it.episode ?: Int.MAX_VALUE },
    { it.name.orEmpty() },
)

private fun Episode.displayTitle(): String {
    val index = when {
        season != null && episode != null -> "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        episode != null -> "Episode $episode"
        season != null -> "Season $season"
        else -> "Episode"
    }
    return name?.takeIf(String::isNotBlank)?.let { "$index · $it" } ?: index
}
