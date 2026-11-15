package com.tvku.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvku.app.navigation.TvkuNavHost
import com.tvku.core.designsystem.theme.TvkuTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep the system splash until local preferences are read, so the
        // first frame is already the right screen (no onboarding flash).
        splash.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }
        enableEdgeToEdge()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            TvkuTheme {
                Box(Modifier.fillMaxSize().background(TvkuTheme.colors.background)) {
                    (state as? MainUiState.Ready)?.let { TvkuNavHost(onboardingCompleted = it.onboardingCompleted) }
                }
            }
        }
    }
}
