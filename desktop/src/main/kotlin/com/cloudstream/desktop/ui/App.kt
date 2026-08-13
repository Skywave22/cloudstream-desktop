package com.cloudstream.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cloudstream.desktop.playback.PlaybackSource
import com.cloudstream.desktop.ui.screens.DetailsScreen
import com.cloudstream.desktop.ui.screens.HomeScreen
import com.cloudstream.desktop.ui.screens.PlayerScreen
import com.cloudstream.desktop.ui.screens.SearchScreen
import com.cloudstream.desktop.ui.screens.rememberDetailsScreenState
import com.cloudstream.desktop.ui.screens.rememberHomeScreenState
import com.cloudstream.desktop.ui.screens.rememberSearchScreenState
import com.lagradost.cloudstream3.SearchResponse

enum class Screen { Home, Search, Details, Player }

@Composable
fun App() {
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    var detailsBackScreen by remember { mutableStateOf(Screen.Home) }
    var selectedItem by remember { mutableStateOf<SearchResponse?>(null) }
    var playbackSource by remember { mutableStateOf<PlaybackSource?>(null) }
    val homeState = rememberHomeScreenState()
    val searchState = rememberSearchScreenState()
    val detailsState = rememberDetailsScreenState(selectedItem)

    fun openDetails(item: SearchResponse, backScreen: Screen) {
        selectedItem = item
        detailsBackScreen = backScreen
        currentScreen = Screen.Details
    }

    MaterialTheme(
        colors = darkColors(
            primary = Color(0xFF9C27B0),
            surface = Color(0xFF1E1E1E),
            background = Color(0xFF121212),
            onSurface = Color.White,
            onBackground = Color.White,
        ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("CloudStream Desktop") },
                    backgroundColor = MaterialTheme.colors.surface,
                    contentColor = MaterialTheme.colors.onSurface,
                    elevation = 8.dp,
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentScreen) {
                    Screen.Home -> HomeScreen(
                        state = homeState,
                        onSearch = { currentScreen = Screen.Search },
                        onOpen = { openDetails(it, Screen.Home) },
                    )

                    Screen.Search -> SearchScreen(
                        state = searchState,
                        onBack = { currentScreen = Screen.Home },
                        onOpen = { openDetails(it, Screen.Search) },
                    )

                    Screen.Details -> selectedItem?.let { item ->
                        DetailsScreen(
                            state = detailsState,
                            item = item,
                            onBack = { currentScreen = detailsBackScreen },
                            onPlay = {
                                playbackSource = it
                                currentScreen = Screen.Player
                            },
                        )
                    } ?: HomeScreen(
                        state = homeState,
                        onSearch = { currentScreen = Screen.Search },
                        onOpen = { openDetails(it, Screen.Home) },
                    )

                    Screen.Player -> playbackSource?.let { source ->
                        PlayerScreen(
                            source = source,
                            onBack = {
                                currentScreen = if (selectedItem == null) Screen.Home else Screen.Details
                            },
                        )
                    } ?: HomeScreen(
                        state = homeState,
                        onSearch = { currentScreen = Screen.Search },
                        onOpen = { openDetails(it, Screen.Home) },
                    )
                }
            }
        }
    }
}
