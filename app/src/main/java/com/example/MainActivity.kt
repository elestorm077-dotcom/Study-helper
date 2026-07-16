package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.StudyViewModel

enum class Screen {
    Auth,
    Chat,
    History
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: StudyViewModel = viewModel()
                var currentScreen by remember { mutableStateOf(Screen.Auth) }

                // Synchronize login status on start
                val isLoggedIn by viewModel.isLoggedIn.collectAsState()
                LaunchedEffect(isLoggedIn) {
                    currentScreen = if (isLoggedIn) Screen.Chat else Screen.Auth
                }

                // Handle back press gracefully
                BackHandler(enabled = currentScreen != Screen.Auth) {
                    when (currentScreen) {
                        Screen.History -> currentScreen = Screen.Chat
                        Screen.Chat -> {
                            // If user is guest/logged in, let back press exit normally
                            finish()
                        }
                        else -> {}
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Crossfade(
                        targetState = currentScreen,
                        animationSpec = tween(durationMillis = 350),
                        label = "ScreenTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { screen ->
                        when (screen) {
                            Screen.Auth -> {
                                AuthScreen(
                                    viewModel = viewModel,
                                    onAuthSuccess = { currentScreen = Screen.Chat }
                                )
                            }
                            Screen.Chat -> {
                                ChatScreen(
                                    viewModel = viewModel,
                                    onNavigateToHistory = { currentScreen = Screen.History }
                                )
                            }
                            Screen.History -> {
                                HistoryScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = Screen.Chat }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
