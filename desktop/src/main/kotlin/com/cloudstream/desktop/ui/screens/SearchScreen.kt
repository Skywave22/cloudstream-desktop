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
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val DEFAULT_SEARCH_TIMEOUT_MS = 60_000L

class SearchScreenState internal constructor() {
    var query by mutableStateOf("")
        internal set
    internal var results by mutableStateOf(emptyList<SearchResponse>())
    internal var errors by mutableStateOf(emptyList<String>())
    internal var loading by mutableStateOf(false)
    internal var hasSearched by mutableStateOf(false)
}

@Composable
fun rememberSearchScreenState(): SearchScreenState = remember { SearchScreenState() }

internal data class ProviderSearchResult(
    val items: List<SearchResponse> = emptyList(),
    val error: String? = null,
)

@Composable
fun SearchScreen(
    state: SearchScreenState,
    onBack: () -> Unit,
    onOpen: (SearchResponse) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun search() {
        val normalizedQuery = state.query.trim()
        if (normalizedQuery.isEmpty() || state.loading) return

        searchJob?.cancel()
        searchJob = scope.launch {
            state.loading = true
            state.hasSearched = true
            state.errors = emptyList()
            try {
                val providers = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
                val providerResults = coroutineScope {
                    providers.map { api ->
                        async { api.searchSafely(normalizedQuery) }
                    }.awaitAll()
                }
                state.results = providerResults
                    .flatMap(ProviderSearchResult::items)
                    .distinctBy { "${it.apiName}\u0000${it.url}" }
                state.errors = providerResults.mapNotNull(ProviderSearchResult::error)
            } finally {
                state.loading = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { searchJob?.cancel() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = ::search,
                enabled = state.query.isNotBlank() && !state.loading,
            ) {
                Text("Go")
            }
        }
        Button(onClick = onBack) { Text("Back") }
        Spacer(modifier = Modifier.height(8.dp))

        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.errors) { message ->
                Text(message, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
            }

            items(state.results, key = { "${it.apiName}\u0000${it.url}" }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colors.surface,
                    elevation = 4.dp,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.name, style = MaterialTheme.typography.h6)
                        Text(item.apiName, style = MaterialTheme.typography.caption)
                        Button(
                            onClick = { onOpen(item) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Open details")
                        }
                    }
                }
            }

            if (state.hasSearched && !state.loading && state.results.isEmpty() && state.errors.isEmpty()) {
                item { Text("No results found.") }
            }
        }
    }
}

internal suspend fun MainAPI.searchSafely(query: String): ProviderSearchResult = try {
    val response = withTimeout(searchTimeoutMs ?: DEFAULT_SEARCH_TIMEOUT_MS) {
        // Calling the paginated overload supports providers that implement either search API.
        search(query, 1)
    }
    ProviderSearchResult(response?.items.orEmpty())
} catch (_: TimeoutCancellationException) {
    ProviderSearchResult(error = "$name: search timed out")
} catch (error: CancellationException) {
    throw error
} catch (_: NotImplementedError) {
    ProviderSearchResult(error = "$name: search is not implemented")
} catch (error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
    ProviderSearchResult(error = "$name: ${error.message ?: "search failed"}")
}
