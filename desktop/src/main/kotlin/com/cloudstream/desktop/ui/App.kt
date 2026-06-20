package com.cloudstream.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cloudstream.desktop.ui.screens.HomeScreen
import com.cloudstream.desktop.ui.screens.SearchScreen
import com.cloudstream.desktop.ui.screens.PlayerScreen

enum class Screen { Home, Search, Player }

@Composable
fun App() {
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    var playerUrl by remember { mutableStateOf("") }

    MaterialTheme(
        colors = darkColors(
            primary = androidx.compose.ui.graphics.Color(0xFF9C27B0),
            surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            background = androidx.compose.ui.graphics.Color(0xFF121212),
            onSurface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            onBackground = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("CloudStream Desktop") },
                    backgroundColor = MaterialTheme.colors.surface,
                    contentColor = MaterialTheme.colors.onSurface,
                    elevation = 8.dp
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentScreen) {
                    Screen.Home -> HomeScreen(
                        onSearch = { currentScreen = Screen.Search },
                        onPlay = { url ->
                            playerUrl = url
                            currentScreen = Screen.Player
                        }
                    )
                    Screen.Search -> SearchScreen(
                        onBack = { currentScreen = Screen.Home },
                        onPlay = { url ->
                            playerUrl = url
                            currentScreen = Screen.Player
                        }
                    )
                    Screen.Player -> PlayerScreen(
                        url = playerUrl,
                        onBack = { currentScreen = Screen.Home }
                    )
                }
            }
        }
    }
}
