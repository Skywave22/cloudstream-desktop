package com.cloudstream.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.APIHolder
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onPlay: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<com.lagradost.cloudstream3.SearchResponse>()) }
    var loading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                loading = true
                scope.launch {
                    results = APIHolder.allProviders.flatMap { api ->
                        try {
                            api.search(query)?.toList() ?: emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    loading = false
                }
            }) {
                Text("Go")
            }
        }
        Button(onClick = onBack) { Text("Back") }
        Spacer(modifier = Modifier.height(8.dp))

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn {
            items(results) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    backgroundColor = MaterialTheme.colors.surface,
                    elevation = 4.dp
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.name, style = MaterialTheme.typography.h6)
                        Text(item.apiName, style = MaterialTheme.typography.caption)
                        Button(onClick = {
                            // In a full port, this would call api.load(item.url) -> loadLinks() -> play
                        }) {
                            Text("Load Details")
                        }
                    }
                }
            }
        }
    }
}
