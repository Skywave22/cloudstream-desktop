package com.cloudstream.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cloudstream.desktop.playback.PlaybackSource
import com.cloudstream.desktop.playback.playableMedia
import com.cloudstream.desktop.playback.resolvePlaybackSources
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class DetailsScreenState internal constructor() {
    internal var details by mutableStateOf<LoadResponse?>(null)
    internal var loading by mutableStateOf(false)
    internal var loaded by mutableStateOf(false)
    internal var loadError by mutableStateOf<String?>(null)
    internal var resolving by mutableStateOf(false)
    internal var resolvingTitle by mutableStateOf<String?>(null)
    internal var sources by mutableStateOf(emptyList<PlaybackSource>())
    internal var subtitleCount by mutableStateOf(0)
    internal var linkError by mutableStateOf<String?>(null)
}

@Composable
fun rememberDetailsScreenState(item: SearchResponse?): DetailsScreenState =
    remember(item?.apiName, item?.url) { DetailsScreenState() }

@Composable
fun DetailsScreen(
    state: DetailsScreenState,
    item: SearchResponse,
    onBack: () -> Unit,
    onPlay: (PlaybackSource) -> Unit,
) {
    val provider = remember(item.apiName) { APIHolder.getApiFromNameNull(item.apiName) }
    val scope = rememberCoroutineScope()
    var resolutionJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(provider, item.url) {
        if (state.loaded || state.loading) return@LaunchedEffect

        state.loading = true
        state.loadError = null
        state.details = null
        var completed = false
        try {
            if (provider == null) {
                state.loadError = "The ${item.apiName} provider is no longer loaded."
            } else {
                state.details = withTimeout(provider.loadTimeoutMs ?: 60_000L) {
                    provider.load(item.url)
                }
                if (state.details == null) {
                    state.loadError = "The provider did not return details for this item."
                }
            }
            completed = true
        } catch (_: TimeoutCancellationException) {
            state.loadError = "Loading details timed out."
            completed = true
        } catch (error: CancellationException) {
            throw error
        } catch (_: NotImplementedError) {
            state.loadError = "This provider does not implement a details page."
            completed = true
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            state.loadError = error.userMessage("Could not load details.")
            completed = true
        } finally {
            state.loading = false
            state.loaded = completed
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            resolutionJob?.cancel()
            state.resolving = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) { Text("Back") }
            Text(item.apiName, style = MaterialTheme.typography.caption)
        }
        Spacer(Modifier.height(12.dp))

        when {
            state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            state.loadError != null -> Text(state.loadError.orEmpty(), color = MaterialTheme.colors.error)
            state.details != null -> {
                val loaded = state.details ?: return@Column
                val metadataOnly = provider?.providerType == ProviderType.MetaProvider
                val playable = if (metadataOnly) emptyList() else loaded.playableMedia()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(loaded.name, style = MaterialTheme.typography.h5)
                        val metadata = listOfNotNull(
                            loaded.year?.toString(),
                            loaded.contentRating,
                            loaded.duration?.let { "$it min" },
                        ).joinToString(" · ")
                        if (metadata.isNotBlank()) {
                            Text(metadata, style = MaterialTheme.typography.caption)
                        }
                        loaded.plot?.takeIf(String::isNotBlank)?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.body1)
                        }
                        loaded.tags?.takeIf { it.isNotEmpty() }?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it.joinToString(" · "), style = MaterialTheme.typography.caption)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Episodes and media", style = MaterialTheme.typography.h6)
                    }

                    if (playable.isEmpty()) {
                        item {
                            Text(
                                when {
                                    metadataOnly -> "This is a metadata result. Choose the same title from a streaming extension to resolve media."
                                    loaded.comingSoon -> "This title is not available yet."
                                    else -> "This provider returned details but no playable media."
                                },
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                            )
                        }
                    } else {
                        items(playable, key = { "${it.title}\u0000${it.data}" }) { media ->
                            OutlinedButton(
                                onClick = {
                                    val activeProvider = provider ?: return@OutlinedButton
                                    resolutionJob?.cancel()
                                    resolutionJob = scope.launch {
                                        state.resolving = true
                                        state.resolvingTitle = media.title
                                        state.sources = emptyList()
                                        state.subtitleCount = 0
                                        state.linkError = null
                                        try {
                                            val result = resolvePlaybackSources(activeProvider, media.data)
                                            state.sources = result.sources
                                            state.subtitleCount = result.subtitles.size
                                            state.linkError = result.error
                                        } finally {
                                            state.resolving = false
                                        }
                                    }
                                },
                                enabled = !state.resolving,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(media.title)
                            }
                        }
                    }

                    if (state.resolving) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator()
                                Text(
                                    "Resolving ${state.resolvingTitle.orEmpty()}…",
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }

                    state.linkError?.let { message ->
                        item { Text(message, color = MaterialTheme.colors.error) }
                    }

                    if (state.sources.isNotEmpty()) {
                        item {
                            Text("Available streams", style = MaterialTheme.typography.h6)
                            if (state.subtitleCount > 0) {
                                Text(
                                    "${state.subtitleCount} subtitle track(s) found",
                                    style = MaterialTheme.typography.caption,
                                )
                            }
                        }
                        items(state.sources, key = { "${it.url}\u0000${it.name}\u0000${it.quality}" }) { source ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = MaterialTheme.colors.surface,
                                elevation = 3.dp,
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(source.displayName)
                                    Text(source.source, style = MaterialTheme.typography.caption)
                                    if (source.requiresCustomHeaders) {
                                        Text(
                                            "This stream requires request headers; JavaFX playback may depend on host CORS support.",
                                            style = MaterialTheme.typography.caption,
                                            color = MaterialTheme.colors.secondary,
                                        )
                                    }
                                    Button(
                                        onClick = { onPlay(source) },
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) {
                                        Text("Play")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Throwable.userMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: fallback
