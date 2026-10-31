package uk.co.fnurble.nfas.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.domain.RoundType
import uk.co.fnurble.nfas.ui.screens.AccountScreen
import uk.co.fnurble.nfas.ui.screens.HistoryScreen
import uk.co.fnurble.nfas.ui.screens.HomeScreen
import uk.co.fnurble.nfas.ui.screens.NewRoundScreen
import uk.co.fnurble.nfas.ui.screens.ProfileScreen
import uk.co.fnurble.nfas.ui.screens.RoundTypeScreen
import uk.co.fnurble.nfas.ui.screens.ScoreRoundScreen
import uk.co.fnurble.nfas.ui.screens.ScorecardScreen
import uk.co.fnurble.nfas.ui.screens.ScoringScreen

private const val ROUTE_SCORING = "round/{roundId}?${ScoringViewModel.ARG_START_TARGET}={${ScoringViewModel.ARG_START_TARGET}}"
private const val ROUTE_SCORECARD = "card/{roundId}"

/** Scoring screen for a round; [targetNo] 0 opens the next unscored target. */
private fun scoringRoute(roundId: String, targetNo: Int = 0) =
    "round/$roundId?${ScoringViewModel.ARG_START_TARGET}=$targetNo"

private fun scorecardRoute(roundId: String) = "card/$roundId"

@Composable
fun NfasNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = appViewModel { c, _ -> HomeViewModel(c.roundRepository, c.accountRepository, c.roundSyncer, c.accountStore) },
                onProfile = { nav.navigate("profile") },
                onScoreRound = { nav.navigate("scoreRound") },
                onHistory = { nav.navigate("history") },
            )
        }
        // Continue an unfinished round or start a new one.
        composable("scoreRound") {
            ScoreRoundScreen(
                vm = appViewModel { c, _ -> ScoreRoundViewModel(c.profileRepository, c.roundRepository) },
                onBack = { nav.popBackStack() },
                onContinue = { r -> nav.navigate(scoringRoute(r.id)) },
                onStartNew = { nav.navigate("roundType") },
                onProfile = { nav.navigate("profile") },
            )
        }
        composable("roundType") {
            RoundTypeScreen(
                onBack = { nav.popBackStack() },
                onChoose = { type -> nav.navigate("newRound/${type.name}") },
            )
        }
        // Finished rounds only; each opens its scorecard.
        composable("history") {
            HistoryScreen(
                vm = appViewModel { c, _ -> HistoryViewModel(c.roundRepository, c.accountRepository, c.roundSyncer, c.accountStore) },
                onBack = { nav.popBackStack() },
                onOpenRound = { r ->
                    nav.navigate(if (r.status == RoundStatus.COMPLETED) scorecardRoute(r.id) else scoringRoute(r.id))
                },
            )
        }
        composable("profile") {
            ProfileScreen(
                vm = appViewModel { c, _ -> ProfileViewModel(c.profileRepository, c.accountRepository) },
                onBack = { nav.popBackStack() },
                onAccount = { nav.navigate("account") },
            )
        }
        composable("account") {
            AccountScreen(
                vm = appViewModel { c, _ -> AccountViewModel(c.accountRepository, c.profileRepository) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "newRound/{roundType}",
            arguments = listOf(navArgument("roundType") { type = NavType.StringType }),
        ) { entry ->
            val roundType = entry.arguments?.getString("roundType")
                ?.let { name -> RoundType.entries.firstOrNull { it.name == name } } ?: RoundType.BIG_GAME
            NewRoundScreen(
                vm = appViewModel { c, _ -> NewRoundViewModel(c.profileRepository, c.roundRepository) },
                roundType = roundType,
                onBack = { nav.popBackStack() },
                onEditProfile = { nav.navigate("profile") },
                onStarted = { id ->
                    nav.navigate(scoringRoute(id)) { popUpTo("home") }
                },
            )
        }
        composable(
            ROUTE_SCORING,
            arguments = listOf(
                navArgument("roundId") { type = NavType.StringType },
                navArgument(ScoringViewModel.ARG_START_TARGET) { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val roundId = entry.arguments?.getString("roundId").orEmpty()
            ScoringScreen(
                vm = appViewModel { c, saved -> ScoringViewModel(c.roundRepository, saved) },
                onBack = { nav.popBackStack() },
                // Finishing shows the scorecard; back from there goes home.
                onFinished = { nav.navigate(scorecardRoute(roundId)) { popUpTo("home") } },
            )
        }
        composable(
            ROUTE_SCORECARD,
            arguments = listOf(navArgument("roundId") { type = NavType.StringType }),
        ) { entry ->
            val roundId = entry.arguments?.getString("roundId").orEmpty()
            ScorecardScreen(
                vm = appViewModel { c, saved -> ScorecardViewModel(c.roundRepository, saved) },
                onBack = { nav.popBackStack() },
                onEdit = { targetNo -> nav.navigate(scoringRoute(roundId, targetNo)) },
            )
        }
    }
}
