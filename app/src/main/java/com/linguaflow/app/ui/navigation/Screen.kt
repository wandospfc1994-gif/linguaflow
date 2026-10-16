package com.linguaflow.app.ui.navigation

sealed class Screen(val route: String, val title: String) {
    data object Home : Screen("home", "Início")
    data object Chat : Screen("chat", "Conversação")
    data object DailyChallenge : Screen("challenge", "Desafio Diário")
    data object LevelProgress : Screen("progress", "Progresso")
    data object Onboarding : Screen("onboarding", "Boas-vindas")
}
