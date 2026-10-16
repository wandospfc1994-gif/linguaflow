package com.linguaflow.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.linguaflow.app.data.download.ModelDownloadManager
import com.linguaflow.app.data.repository.LinguaflowRepository
import com.linguaflow.app.engine.TextToSpeechEngine
import com.linguaflow.app.engine.TutorLlmEngine
import com.linguaflow.app.ui.screens.ChatScreen
import com.linguaflow.app.ui.screens.DailyChallengeScreen
import com.linguaflow.app.ui.screens.HomeScreen
import com.linguaflow.app.ui.screens.LevelProgressScreen
import com.linguaflow.app.ui.screens.OnboardingScreen

@Composable
fun LinguaflowNavGraph(
    navController: NavHostController,
    repository: LinguaflowRepository,
    modelManager: ModelDownloadManager,
    ttsEngine: TextToSpeechEngine,
    tutorLlm: TutorLlmEngine,
    sttEngine: com.linguaflow.app.engine.SpeechToTextEngine,
    startDestination: String = if (modelManager.hasMinimumRequiredModels()) Screen.Home.route else Screen.Onboarding.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                repository = repository,
                modelManager = modelManager,
                onNavigateToChat = { navController.navigate(Screen.Chat.route) },
                onNavigateToChallenge = { navController.navigate(Screen.DailyChallenge.route) },
                onNavigateToProgress = { navController.navigate(Screen.LevelProgress.route) },
                onNavigateToOnboarding = { navController.navigate(Screen.Onboarding.route) }
            )
        }

        composable(Screen.Chat.route) {
            ChatScreen(
                repository = repository,
                ttsEngine = ttsEngine,
                tutorLlm = tutorLlm,
                sttEngine = sttEngine,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.DailyChallenge.route) {
            DailyChallengeScreen(
                repository = repository,
                ttsEngine = ttsEngine,
                sttEngine = sttEngine,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.LevelProgress.route) {
            LevelProgressScreen(
                repository = repository,
                ttsEngine = ttsEngine,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                repository = repository,
                modelManager = modelManager,
                onFinish = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
