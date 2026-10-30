package uk.co.fnurble.nfas.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uk.co.fnurble.nfas.ui.screens.HomeScreen
import uk.co.fnurble.nfas.ui.screens.NewRoundScreen
import uk.co.fnurble.nfas.ui.screens.ProfileScreen
import uk.co.fnurble.nfas.ui.screens.ScoringScreen

@Composable
fun NfasNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = appViewModel { c, _ -> HomeViewModel(c.profileRepository, c.roundRepository) },
                onProfile = { nav.navigate("profile") },
                onNewRound = { nav.navigate("newRound") },
                onOpenRound = { id -> nav.navigate("round/$id") },
            )
        }
        composable("profile") {
            ProfileScreen(
                vm = appViewModel { c, _ -> ProfileViewModel(c.profileRepository) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("newRound") {
            NewRoundScreen(
                vm = appViewModel { c, _ -> NewRoundViewModel(c.profileRepository, c.roundRepository) },
                onBack = { nav.popBackStack() },
                onEditProfile = { nav.navigate("profile") },
                onStarted = { id ->
                    nav.navigate("round/$id") { popUpTo("home") }
                },
            )
        }
        composable(
            "round/{roundId}",
            arguments = listOf(navArgument("roundId") { type = NavType.StringType }),
        ) {
            ScoringScreen(
                vm = appViewModel { c, saved -> ScoringViewModel(c.roundRepository, saved) },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
