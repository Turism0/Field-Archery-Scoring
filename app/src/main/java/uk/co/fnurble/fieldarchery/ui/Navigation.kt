package uk.co.fnurble.fieldarchery.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uk.co.fnurble.fieldarchery.domain.RoundStatus
import uk.co.fnurble.fieldarchery.domain.RoundType
import uk.co.fnurble.fieldarchery.ui.screens.AccountScreen
import uk.co.fnurble.fieldarchery.ui.screens.AdminDuplicatesScreen
import uk.co.fnurble.fieldarchery.ui.screens.AdminLocationsScreen
import uk.co.fnurble.fieldarchery.ui.screens.AdminScreen
import uk.co.fnurble.fieldarchery.ui.screens.EditRoundScreen
import uk.co.fnurble.fieldarchery.ui.screens.FriendRoundsScreen
import uk.co.fnurble.fieldarchery.ui.screens.FriendScorecardScreen
import uk.co.fnurble.fieldarchery.ui.screens.FriendsScreen
import uk.co.fnurble.fieldarchery.ui.screens.AdminHandicapScreen
import uk.co.fnurble.fieldarchery.ui.screens.HandicapCalculatorScreen
import uk.co.fnurble.fieldarchery.ui.screens.HistoryScreen
import uk.co.fnurble.fieldarchery.ui.screens.HomeScreen
import uk.co.fnurble.fieldarchery.ui.screens.JoinScreen
import uk.co.fnurble.fieldarchery.ui.screens.LobbyScreen
import uk.co.fnurble.fieldarchery.ui.screens.LocationMapScreen
import uk.co.fnurble.fieldarchery.ui.screens.LocationPageScreen
import uk.co.fnurble.fieldarchery.ui.screens.NewRoundScreen
import uk.co.fnurble.fieldarchery.ui.screens.OfferScreen
import uk.co.fnurble.fieldarchery.ui.screens.ProfileScreen
import uk.co.fnurble.fieldarchery.ui.screens.RoundTypeScreen
import uk.co.fnurble.fieldarchery.ui.screens.ScoreRoundScreen
import uk.co.fnurble.fieldarchery.ui.screens.ScorecardScreen
import uk.co.fnurble.fieldarchery.ui.screens.ScoringScreen
import uk.co.fnurble.fieldarchery.ui.screens.StatsScreen

private const val ROUTE_SCORING = "round/{roundId}?${ScoringViewModel.ARG_START_TARGET}={${ScoringViewModel.ARG_START_TARGET}}"
private const val ROUTE_SCORECARD = "card/{roundId}"

/** Scoring screen for a round; [targetNo] 0 opens the next unscored target. */
private fun scoringRoute(roundId: String, targetNo: Int = 0) =
    "round/$roundId?${ScoringViewModel.ARG_START_TARGET}=$targetNo"

private fun scorecardRoute(roundId: String) = "card/$roundId"

@Composable
fun FieldArcheryNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = appViewModel { c, _ -> HomeViewModel(c.roundRepository, c.accountRepository, c.roundSyncer, c.accountStore, c.networkStatus, c::checkServer, c.handicapRepository) },
                onProfile = { nav.navigate("profile") },
                onScoreRound = { nav.navigate("scoreRound") },
                onHistory = { nav.navigate("history") },
                onStats = { nav.navigate("stats") },
                onHandicap = { nav.navigate("handicap") },
                onFriends = { nav.navigate("friends") },
                onAdmin = { nav.navigate("admin") },
                onMap = { nav.navigate("map") },
            )
        }
        // Map of shoot locations → a location's page → your scorecard / a friend's scorecard.
        composable("map") {
            LocationMapScreen(
                vm = appViewModel { c, _ -> LocationMapViewModel(c.locationPageRepository, c.accountRepository) },
                onBack = { nav.popBackStack() },
                onProfile = { nav.navigate("profile") },
                onOpenLocation = { loc -> nav.navigate("location/${loc.id}") },
            )
        }
        // Edit a round's details (date, targets, class, age group, bow style).
        composable(
            "editRound/{roundId}",
            arguments = listOf(navArgument("roundId") { type = NavType.StringType }),
        ) {
            EditRoundScreen(
                vm = appViewModel { c, saved -> EditRoundViewModel(c.roundRepository, saved) },
                onBack = { nav.popBackStack() },
            )
        }
        // Choose a round's location on the map (from the location sheet).
        composable(
            "pickLocation/{roundId}",
            arguments = listOf(navArgument("roundId") { type = NavType.StringType }),
        ) { entry ->
            val roundId = entry.arguments?.getString("roundId").orEmpty()
            val vm = appViewModel { c, _ -> LocationMapViewModel(c.locationPageRepository, c.accountRepository, c.locationRepository) }
            LocationMapScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onProfile = { nav.navigate("profile") },
                onOpenLocation = { loc -> vm.choose(roundId, loc) { nav.popBackStack() } },
                pickMode = true,
                roundId = roundId,
                onPicked = { nav.popBackStack() },
            )
        }
        composable(
            "location/{locationId}",
            arguments = listOf(navArgument("locationId") { type = NavType.StringType }),
        ) {
            LocationPageScreen(
                vm = appViewModel { c, saved -> LocationPageViewModel(c.locationPageRepository, saved, c.handicapRepository) },
                onBack = { nav.popBackStack() },
                onOpenMyRound = { id -> nav.navigate(scorecardRoute(id)) },
                onOpenFriendRound = { f, r -> nav.navigate("friendCard/${f.archerId}/${f.username}/${r.roundId}") },
            )
        }
        // Admin menu: Admins and the Owner only (the tile is hidden for everyone else; the server checks too).
        composable("admin") {
            AdminScreen(
                vm = appViewModel { c, _ -> AdminViewModel(c.adminRepository, c.accountStore) },
                onBack = { nav.popBackStack() },
                onLocations = { nav.navigate("adminLocations") },
                onDuplicates = { nav.navigate("adminDuplicates") },
                onHandicap = { nav.navigate("adminHandicap") },
            )
        }
        composable("adminHandicap") {
            AdminHandicapScreen(
                vm = appViewModel { c, _ -> AdminHandicapViewModel(c.handicapRepository, c.accountStore) },
                onBack = { nav.popBackStack() },
            )
        }
        // Handicap calculator (2.2.05): a score as every bow style and class.
        composable("handicap") {
            HandicapCalculatorScreen(
                vm = appViewModel { c, _ -> HandicapCalculatorViewModel(c.profileRepository, c.handicapRepository) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("adminDuplicates") {
            AdminDuplicatesScreen(
                vm = appViewModel { c, _ -> AdminDuplicatesViewModel(c.adminRepository, c.locationRepository, c.accountStore) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("adminLocations") {
            AdminLocationsScreen(
                vm = appViewModel { c, _ -> AdminLocationsViewModel(c.adminRepository, c.locationRepository, c.accountStore) },
                onBack = { nav.popBackStack() },
            )
        }
        // Friends: list and requests → a friend's finished rounds → their scorecard (read-only).
        composable("friends") {
            FriendsScreen(
                vm = appViewModel { c, _ -> FriendsViewModel(c.friendsRepository, c.accountRepository) },
                onBack = { nav.popBackStack() },
                onProfile = { nav.navigate("profile") },
                onOpenFriend = { f -> nav.navigate("friend/${f.archerId}/${f.username}") },
                onOpenOffer = { o -> nav.navigate("offer/${o.roundId}/${o.fromUsername}") },
            )
        }
        // A round a friend scored for you: scorecard with Accept / Decline.
        composable(
            "offer/{roundId}/{username}",
            arguments = listOf(
                navArgument("roundId") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
            ),
        ) {
            OfferScreen(
                vm = appViewModel { c, saved -> OfferViewModel(c.friendsRepository, saved) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "friend/{archerId}/{username}",
            arguments = listOf(
                navArgument("archerId") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
            ),
        ) { entry ->
            val archerId = entry.arguments?.getString("archerId").orEmpty()
            val username = entry.arguments?.getString("username").orEmpty()
            FriendRoundsScreen(
                vm = appViewModel { c, saved -> FriendRoundsViewModel(c.friendsRepository, saved, c.handicapRepository) },
                onBack = { nav.popBackStack() },
                onOpenRound = { r -> nav.navigate("friendCard/$archerId/$username/${r.roundId}") },
            )
        }
        composable(
            "friendCard/{archerId}/{username}/{roundId}",
            arguments = listOf(
                navArgument("archerId") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
                navArgument("roundId") { type = NavType.StringType },
            ),
        ) {
            FriendScorecardScreen(
                vm = appViewModel { c, saved -> FriendScorecardViewModel(c.friendsRepository, saved, c.handicapRepository) },
                onBack = { nav.popBackStack() },
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
                onJoin = { nav.navigate("join") },
            )
        }
        // Live groups (2.1): join with a code or QR → the group's lobby → scoring once the host starts.
        composable("join") {
            JoinScreen(
                vm = appViewModel { c, _ -> JoinViewModel(c.profileRepository, c.liveGroupRepository) },
                onBack = { nav.popBackStack() },
                onJoined = { id -> nav.navigate("lobby/$id") { popUpTo("home") } },
            )
        }
        composable(
            "lobby/{groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) {
            LobbyScreen(
                vm = appViewModel { c, saved -> LobbyViewModel(c.liveGroupRepository, c.roundRepository, saved) },
                onBack = { nav.popBackStack() },
                onStarted = { id -> nav.navigate(scoringRoute(id)) { popUpTo("home") } },
                onGone = { nav.popBackStack("home", inclusive = false) },
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
        // Personal bests, trends and seasons; each round opens its scorecard.
        composable("stats") {
            StatsScreen(
                vm = appViewModel { c, _ -> StatsViewModel(c.roundRepository, c.handicapRepository) },
                onBack = { nav.popBackStack() },
                onOpenRound = { id -> nav.navigate(scorecardRoute(id)) },
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
                vm = appViewModel { c, _ -> NewRoundViewModel(c.profileRepository, c.roundRepository, c.locationRepository, roundType, c.accountStore, c.liveGroupRepository) },
                roundType = roundType,
                onBack = { nav.popBackStack() },
                onEditProfile = { nav.navigate("profile") },
                onStarted = { id ->
                    nav.navigate(scoringRoute(id)) { popUpTo("home") }
                },
                onLive = { groupId -> nav.navigate("lobby/$groupId") { popUpTo("home") } },
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
                vm = appViewModel { c, saved -> ScoringViewModel(c.roundRepository, saved, c.liveGroupRepository) },
                locationVm = appViewModel { c, saved -> RoundLocationViewModel(c.locationRepository, c.accountRepository, saved, c.locationPageRepository) },
                onBack = { nav.popBackStack() },
                // Finishing shows the scorecard; back from there goes home.
                onFinished = { nav.navigate(scorecardRoute(roundId)) { popUpTo("home") } },
                onPickLocation = { nav.navigate("pickLocation/$roundId") },
                // In a group, the archer being scored now.
                onEditDetails = { id -> nav.navigate("editRound/$id") },
                onLobby = { groupId -> nav.navigate("lobby/$groupId") { popUpTo("home") } },
            )
        }
        composable(
            ROUTE_SCORECARD,
            arguments = listOf(navArgument("roundId") { type = NavType.StringType }),
        ) { entry ->
            val roundId = entry.arguments?.getString("roundId").orEmpty()
            ScorecardScreen(
                vm = appViewModel { c, saved -> ScorecardViewModel(c.roundRepository, saved, c.handicapRepository) },
                locationVm = appViewModel { c, saved -> RoundLocationViewModel(c.locationRepository, c.accountRepository, saved, c.locationPageRepository) },
                onBack = { nav.popBackStack() },
                onEdit = { targetNo -> nav.navigate(scoringRoute(roundId, targetNo)) },
                onPickLocation = { nav.navigate("pickLocation/$roundId") },
                onEditDetails = { nav.navigate("editRound/$roundId") },
                onOpenMember = { id -> nav.navigate(scorecardRoute(id)) },
            )
        }
    }
}
