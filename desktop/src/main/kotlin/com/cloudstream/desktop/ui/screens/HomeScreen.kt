package com.cloudstream.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onSearch: () -> Unit,
    onPlay: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf(APIHolder.allProviders.toList()) }
    var homePages by remember { mutableStateOf(listOf<HomePageList>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        providers = APIHolder.allProviders.toList()
        providers.firstOrNull()?.let { api ->
            if (api.hasMainPage) {
                loading = true
                scope.launch {
                    try {
                        val pageData = api.mainPage.firstOrNull()
                        val request = pageData?.let {
                            MainPageRequest(it.name, it.data, it.horizontalImages)
                        }
                        val res = api.getMainPage(1, request ?: return@launch)
                        res?.let { homePages = it.items }
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        loading = false
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onSearch) { Text("Open Search") }
        Spacer(modifier = Modifier.height(12.dp))

        Text("Loaded Providers: ${providers.size}", style = MaterialTheme.typography.h6)
        providers.forEach { p ->
            Text("- ${p.name} (${p.mainUrl})", style = MaterialTheme.typography.body2)
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        error?.let { Text("Error: $it", color = MaterialTheme.colors.error) }

        LazyColumn {
            items(homePages) { page ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    backgroundColor = MaterialTheme.colors.surface,
                    elevation = 4.dp
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(page.name, style = MaterialTheme.typography.subtitle1)
                        page.list.take(5).forEach { item ->
                            Button(onClick = {
                                if (item.url.endsWith(".m3u8", true) || item.url.endsWith(".mp4", true)) {
                                    onPlay(item.url)
                                }
                            }) {
                                Text(item.name)
                            }
                        }
                    }
                }
            }
        }
    }
}
