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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

private const val DEFAULT_HOME_TIMEOUT_MS = 60_000L

internal data class ProviderPage(
    val providerName: String,
    val page: HomePageList,
)

class HomeScreenState internal constructor() {
    internal var providers by mutableStateOf(emptyList<MainAPI>())
    internal var homePages by mutableStateOf(emptyList<ProviderPage>())
    internal var loading by mutableStateOf(false)
    internal var errors by mutableStateOf(emptyList<String>())
    internal var loaded by mutableStateOf(false)
    internal var refreshVersion by mutableStateOf(0)

    fun refresh() {
        loaded = false
        refreshVersion += 1
    }
}

@Composable
fun rememberHomeScreenState(): HomeScreenState = remember { HomeScreenState() }

private data class HomeLoadResult(
    val pages: List<ProviderPage> = emptyList(),
    val error: String? = null,
)

@Composable
fun HomeScreen(
    state: HomeScreenState,
    onSearch: () -> Unit,
    onOpen: (SearchResponse) -> Unit,
) {
    LaunchedEffect(state.refreshVersion) {
        if (state.loaded || state.loading) return@LaunchedEffect

        state.providers = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        state.loading = true
        state.errors = emptyList()
        try {
            val results = coroutineScope {
                state.providers.filter(MainAPI::hasMainPage).map { api ->
                    async {
                        val pageData = api.mainPage.firstOrNull()
                            ?: return@async HomeLoadResult(error = "${api.name}: no home-page request is configured")
                        val request = MainPageRequest(pageData.name, pageData.data, pageData.horizontalImages)
                        try {
                            val response = withTimeout(api.getMainPageTimeoutMs ?: DEFAULT_HOME_TIMEOUT_MS) {
                                api.getMainPage(1, request)
                            }
                            HomeLoadResult(
                                pages = response?.items.orEmpty().map { ProviderPage(api.name, it) },
                                error = if (response == null) "${api.name}: no home-page data returned" else null,
                            )
                        } catch (_: TimeoutCancellationException) {
                            HomeLoadResult(error = "${api.name}: home page timed out")
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: NotImplementedError) {
                            HomeLoadResult(error = "${api.name}: home page is not implemented")
                        } catch (error: Throwable) {
                            if (error is VirtualMachineError || error is ThreadDeath) throw error
                            HomeLoadResult(error = "${api.name}: ${error.message ?: "failed to load"}")
                        }
                    }
                }.awaitAll()
            }

            state.homePages = results.flatMap(HomeLoadResult::pages)
            state.errors = results.mapNotNull(HomeLoadResult::error)
            state.loaded = true
        } finally {
            state.loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSearch) { Text("Search") }
            Text(
                "${state.providers.size} provider${if (state.providers.size == 1) "" else "s"}",
                style = MaterialTheme.typography.caption,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.providers.isEmpty()) {
                item {
                    Text(
                        "No providers are loaded. Add a JVM-compatible plugin to ~/.cloudstream/plugins.",
                        color = MaterialTheme.colors.error,
                    )
                }
            }

            items(state.errors) { message ->
                Text(message, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
            }

            if (state.errors.isNotEmpty()) {
                item {
                    Button(onClick = state::refresh) { Text("Retry home pages") }
                }
            }

            items(state.homePages) { providerPage ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colors.surface,
                    elevation = 4.dp,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(providerPage.page.name, style = MaterialTheme.typography.h6)
                        Text(providerPage.providerName, style = MaterialTheme.typography.caption)
                        Spacer(Modifier.height(6.dp))
                        providerPage.page.list.take(12).forEach { item ->
                            Button(
                                onClick = { onOpen(item) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            ) {
                                Text(item.name)
                            }
                        }
                    }
                }
            }

            if (state.homePages.isEmpty() && state.errors.isEmpty() && state.providers.isNotEmpty()) {
                item { Text("Loaded providers do not expose a home page. Use Search to browse them.") }
            }
        }
    }
}
