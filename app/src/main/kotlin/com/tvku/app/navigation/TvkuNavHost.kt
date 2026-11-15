package com.tvku.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tvku.feature.home.HomeRoute
import com.tvku.feature.onboarding.OnboardingRoute
import kotlinx.serialization.Serializable

@Serializable data object OnboardingDestination
@Serializable data object HomeDestination

@Composable
fun TvkuNavHost(onboardingCompleted: Boolean) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (onboardingCompleted) HomeDestination else OnboardingDestination,
    ) {
        composable<OnboardingDestination> {
            OnboardingRoute(onFinished = {
                navController.navigate(HomeDestination) {
                    popUpTo<OnboardingDestination> { inclusive = true }
                }
            })
        }
        composable<HomeDestination> { HomeRoute() }
    }
}
