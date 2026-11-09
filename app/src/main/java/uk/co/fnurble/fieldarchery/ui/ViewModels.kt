package uk.co.fnurble.fieldarchery.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.fnurble.fieldarchery.AppContainer
import uk.co.fnurble.fieldarchery.FieldArcheryApp
import uk.co.fnurble.fieldarchery.data.Account
import uk.co.fnurble.fieldarchery.data.AccountStore
import uk.co.fnurble.fieldarchery.data.AdminArcher
import uk.co.fnurble.fieldarchery.data.AdminLocation
import uk.co.fnurble.fieldarchery.data.DuplicatePair
import uk.co.fnurble.fieldarchery.data.AdminRepository
import uk.co.fnurble.fieldarchery.data.FriendRequest
import uk.co.fnurble.fieldarchery.data.FriendRoundSummary
import uk.co.fnurble.fieldarchery.data.FriendsOverview
import uk.co.fnurble.fieldarchery.data.FriendsRepository
import uk.co.fnurble.fieldarchery.data.HandicapRepository
import uk.co.fnurble.fieldarchery.data.HomeLayoutStore
import uk.co.fnurble.fieldarchery.domain.HomeLayout
import uk.co.fnurble.fieldarchery.domain.HomeTileId
import uk.co.fnurble.fieldarchery.data.ResetCode
import uk.co.fnurble.fieldarchery.data.GroupMember
import uk.co.fnurble.fieldarchery.data.LiveGroupRepository
import uk.co.fnurble.fieldarchery.data.LiveGroupState
import uk.co.fnurble.fieldarchery.data.LiveMethod
import uk.co.fnurble.fieldarchery.data.LivePoll
import uk.co.fnurble.fieldarchery.data.NewGroupMember
import uk.co.fnurble.fieldarchery.data.RoundOffer
import uk.co.fnurble.fieldarchery.data.CachedFriend
import uk.co.fnurble.fieldarchery.data.local.GroupRole
import uk.co.fnurble.fieldarchery.data.LocationPage
import uk.co.fnurble.fieldarchery.data.LocationPageRepository
import uk.co.fnurble.fieldarchery.data.LocationRepository
import uk.co.fnurble.fieldarchery.data.MapLocation
import uk.co.fnurble.fieldarchery.data.NetworkStatus
import uk.co.fnurble.fieldarchery.data.ServerState
import uk.co.fnurble.fieldarchery.data.AccountRepository
import uk.co.fnurble.fieldarchery.data.Profile
import uk.co.fnurble.fieldarchery.data.ProfileRepository
import uk.co.fnurble.fieldarchery.data.RoundRepository
import uk.co.fnurble.fieldarchery.data.RoundSummary
import uk.co.fnurble.fieldarchery.data.remote.ApiException
import uk.co.fnurble.fieldarchery.data.sync.RoundSyncer
import uk.co.fnurble.fieldarchery.domain.AccountRole
import uk.co.fnurble.fieldarchery.domain.AccountRules
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.AnimalResult
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.domain.NearbyLocation
import uk.co.fnurble.fieldarchery.domain.HandicapModel
import uk.co.fnurble.fieldarchery.domain.HandicapRating
import uk.co.fnurble.fieldarchery.domain.HandicapStats
import uk.co.fnurble.fieldarchery.domain.handicapRound
import uk.co.fnurble.fieldarchery.domain.BestAverage
import uk.co.fnurble.fieldarchery.domain.PersonalBest
import uk.co.fnurble.fieldarchery.domain.PersonalStats
import uk.co.fnurble.fieldarchery.domain.Round
import uk.co.fnurble.fieldarchery.domain.RoundStatus
import uk.co.fnurble.fieldarchery.domain.RoundType
import uk.co.fnurble.fieldarchery.domain.TargetScore
import uk.co.fnurble.fieldarchery.domain.TargetType
import uk.co.fnurble.fieldarchery.domain.WoodsmanArrow
import uk.co.fnurble.fieldarchery.domain.resolveAgeGroup
import java.time.Instant
import java.time.LocalDate

/** Creates a ViewModel with access to the app container and the nav arguments. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM = viewModel(
    factory = viewModelFactory {
        initializer {
            val app = this[APPLICATION_KEY] as FieldArcheryApp
            create(app.container, createSavedStateHandle())
        }
    },
)

// ---------------- Home ----------------

/**
 * Pull-to-refresh sync shared by the home and history screens: runs the sync now and
 * turns the result into a short message for a snackbar.
 */
abstract class SyncingViewModel(
    private val syncer: RoundSyncer,
    accountStore: AccountStore,
    accounts: AccountRepository,
) : ViewModel() {
    /** Upload labels and "last synced" are only shown when signed in. */
    val signedIn: StateFlow<Boolean> = accounts.account.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), accounts.account.value != null)

    val username: StateFlow<String?> = accounts.account.map { it?.username }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), accounts.account.value?.username)

    val lastSync: StateFlow<Instant?> = accountStore.lastSync

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-off message for a snackbar after pulling to refresh. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() { _message.value = null }

    /** Pull to refresh: upload anything waiting and download changes now, then say how it went. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val report = try {
                syncer.syncAll()
            } catch (e: Exception) {
                // Anything unexpected (e.g. a reply the app can't read): report it rather than crash.
                if (e is kotlinx.coroutines.CancellationException) throw e
                RoundSyncer.Report(RoundSyncer.Outcome.FAILED, e.message ?: e.javaClass.simpleName)
            } finally {
                _refreshing.value = false
            }
            _message.value = when (report.outcome) {
                RoundSyncer.Outcome.DONE ->
                    if (report.skipped > 0) "Synced, but ${report.skipped} round(s) couldn't be read. Update the app?"
                    else "Up to date"
                RoundSyncer.Outcome.NOT_SIGNED_IN -> "Sign in (Profile) to back up and sync your rounds."
                RoundSyncer.Outcome.RETRY_LATER -> report.message ?: "The server isn't available at the moment. It'll try again."
                RoundSyncer.Outcome.SIGNED_OUT -> "Your sign-in has expired. Sign in again in Profile."
                RoundSyncer.Outcome.FAILED -> "Sync problem: ${report.message}"
            }
        }
    }
}

/** Figures for the home screen tiles. */
data class HomeSummary(val inProgress: Int, val finished: Int, val best: Int?)

/** Home: tiles for Score a round, Score history and Friends, plus account / sync status. */
class HomeViewModel(
    roundRepo: RoundRepository,
    accounts: AccountRepository,
    syncer: RoundSyncer,
    accountStore: AccountStore,
    network: NetworkStatus,
    private val serverCheck: suspend () -> Unit,
    handicap: HandicapRepository? = null,
    private val layoutStore: HomeLayoutStore? = null,
) : SyncingViewModel(syncer, accountStore, accounts) {
    // ---- Arranging the tiles (2.4.01) ----

    /** The order and hidden tiles as saved. */
    val layout: StateFlow<HomeLayout> = layoutStore?.layout ?: MutableStateFlow(HomeLayout())

    private val _editing = MutableStateFlow<HomeLayout?>(null)
    /** While arranging: the layout being changed (saved on Done); null otherwise. */
    val editing: StateFlow<HomeLayout?> = _editing.asStateFlow()

    fun startArranging() { _editing.value = layout.value }
    fun moveTile(id: HomeTileId, by: Int, shown: Collection<HomeTileId>) = _editing.update { it?.move(id, by, shown) }
    fun setTileHidden(id: HomeTileId, hide: Boolean) = _editing.update { it?.setHidden(id, hide) }
    fun resetTiles() { _editing.value = HomeLayout() }
    fun doneArranging() {
        _editing.value?.let { layoutStore?.save(it) }
        _editing.value = null
    }

    /** Your handicap rating, for the Stats tile (null until there's a model and 3+ Big Game rounds). */
    val rating: StateFlow<HandicapRating?> = handicap?.let { h ->
        combine(roundRepo.finishedRounds, h.model) { rounds, m -> m?.let { HandicapStats.rating(it, rounds.mapNotNull { r -> r.handicapRound() }) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } ?: MutableStateFlow(null)

    /** For the "Device offline" / "Server unavailable" notices. */
    val online: StateFlow<Boolean> = network.online
    val server: StateFlow<ServerState> = network.server

    fun checkServer() {
        viewModelScope.launch { serverCheck() }
    }

    val summary: StateFlow<HomeSummary?> = roundRepo.rounds.map { list ->
        val finished = list.filter { it.status == RoundStatus.COMPLETED }
        HomeSummary(
            inProgress = list.count { it.status == RoundStatus.IN_PROGRESS },
            finished = finished.size,
            best = finished.maxOfOrNull { it.totalPoints },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Admins and the Owner get the Admin tile. */
    val role: StateFlow<AccountRole> = accountStore.role
    /** Possible duplicate locations waiting (badge on the Admin tile). */
    val duplicateLocations: StateFlow<Int> = accountStore.duplicateLocations
    /** Rounds friends have scored for you, waiting for an answer (badge on the Friends tile). */
    val roundOffers: StateFlow<Int> = accountStore.roundOffers

    init {
        // Guests' scores in group rounds are only kept for 30 days.
        viewModelScope.launch { roundRepo.deleteOldGuests() }
        // The handicap model: downloaded when there's signal, at most daily (works offline after that).
        handicap?.let { viewModelScope.launch { it.refreshIfStale() } }
    }
}

/** History filters (2.2): null = any. */
data class HistoryFilter(
    val type: RoundType? = null,
    val bowStyle: BowStyle? = null,
    /** Location name as shown on the round. */
    val location: String? = null,
    val period: PersonalStats.Period = PersonalStats.Period.All,
) {
    val isActive: Boolean get() = this != HistoryFilter()

    fun matches(r: RoundSummary, today: LocalDate): Boolean =
        (type == null || r.roundType == type) &&
            (bowStyle == null || r.bowStyle == bowStyle) &&
            (location == null || r.locationName == location) &&
            period.contains(r.shootDate, today)
}

/** What the history filters can offer: only things that are in your rounds. */
data class HistoryChoices(
    val types: List<RoundType> = emptyList(),
    val bowStyles: List<BowStyle> = emptyList(),
    val locations: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
)

/** Score history: finished rounds only, newest first, with filters and personal-best marks. */
class HistoryViewModel(
    roundRepo: RoundRepository,
    accounts: AccountRepository,
    syncer: RoundSyncer,
    accountStore: AccountStore,
) : SyncingViewModel(syncer, accountStore, accounts) {
    private val finished = roundRepo.rounds.map { list -> list.filter { it.status == RoundStatus.COMPLETED } }

    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()
    fun setFilter(change: (HistoryFilter) -> HistoryFilter) = _filter.update(change)
    fun clearFilter() { _filter.value = HistoryFilter() }

    /** Finished rounds matching the filters. */
    val rounds: StateFlow<List<RoundSummary>?> = combine(finished, _filter) { list, f ->
        val today = LocalDate.now()
        list.filter { f.matches(it, today) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many finished rounds there are before filtering. */
    val totalCount: StateFlow<Int> = finished.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val choices: StateFlow<HistoryChoices> = finished.map { list ->
        HistoryChoices(
            types = list.map { it.roundType }.distinct().sortedBy { it.ordinal },
            bowStyles = list.mapNotNull { it.bowStyle }.distinct().sortedBy { it.ordinal },
            locations = list.mapNotNull { it.locationName?.takeIf { n -> n.isNotBlank() } }.distinct().sortedBy { it.lowercase() },
            years = PersonalStats.years(list.map { it.shootDate }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryChoices())

    /** Rounds that were a new personal best when shot (marked "PB"). */
    val personalBests: StateFlow<Set<String>> = roundRepo.finishedRounds.map { PersonalStats.personalBestIds(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}

/** Stats (2.2): personal bests, trends and season summaries from your finished rounds. */
class StatsViewModel(roundRepo: RoundRepository, handicap: HandicapRepository? = null) : ViewModel() {
    val handicapModel: StateFlow<HandicapModel?> = handicap?.model ?: MutableStateFlow(null)

    /** Your rating: average handicap points (out of 1000) of your last 10 Big Game rounds. */
    val rating: StateFlow<HandicapRating?> = combine(roundRepo.finishedRounds, handicapModel) { rounds, m ->
        m?.let { HandicapStats.rating(it, rounds.mapNotNull { r -> r.handicapRound() }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Your latest Big Game rounds with their equivalents (what the rating is made of). */
    val ratingRounds: StateFlow<List<Pair<Round, Int>>> = combine(roundRepo.finishedRounds, handicapModel) { rounds, m ->
        if (m == null) emptyList()
        else rounds.mapNotNull { r -> r.handicapRound()?.let { h -> r to HandicapStats.points(m, h) } }
            .sortedByDescending { it.first.shootDate }.take(HandicapModel.RATING_ROUNDS)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rounds: StateFlow<List<Round>?> = roundRepo.finishedRounds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bests: StateFlow<List<PersonalBest>> = roundRepo.finishedRounds.map { PersonalStats.bests(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bestAverages: StateFlow<List<BestAverage>> = roundRepo.finishedRounds.map { PersonalStats.bestAverages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val seasons: StateFlow<List<PersonalStats.Season>> = roundRepo.finishedRounds.map { PersonalStats.seasons(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Trend chart: one round type, one bow style (or all) ----

    private val _trendType = MutableStateFlow<RoundType?>(null)
    private val _trendBow = MutableStateFlow<BowStyle?>(null)
    private val _trendBowChosen = MutableStateFlow(false)

    /** The round type on the chart: chosen, else the one you've shot most. */
    val trendType: StateFlow<RoundType> = combine(rounds, _trendType) { list, chosen ->
        chosen ?: list.orEmpty().groupingBy { it.type }.eachCount().maxByOrNull { it.value }?.key ?: RoundType.BIG_GAME
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoundType.BIG_GAME)

    /** Bow style on the chart: chosen (null = all), else the one you shoot most with this type. */
    val trendBow: StateFlow<BowStyle?> = combine(rounds, trendType, _trendBow, _trendBowChosen) { list, type, chosen, wasChosen ->
        if (wasChosen) chosen
        else list.orEmpty().filter { it.type == type }.groupingBy { it.bowStyle }.eachCount().maxByOrNull { it.value }?.key
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val trend: StateFlow<List<PersonalStats.TrendPoint>> = combine(rounds, trendType, trendBow) { list, type, bow ->
        PersonalStats.trend(list.orEmpty(), type, bow)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun chooseTrendType(type: RoundType) {
        _trendType.value = type
        _trendBowChosen.value = false
    }

    fun chooseTrendBow(bow: BowStyle?) {
        _trendBow.value = bow
        _trendBowChosen.value = true
    }
}

/** Score a round: continue an unfinished round or start a new one. */
class ScoreRoundViewModel(profiles: ProfileRepository, roundRepo: RoundRepository) : ViewModel() {
    val inProgress: StateFlow<List<RoundSummary>?> = roundRepo.rounds
        .map { list -> list.filter { it.status == RoundStatus.IN_PROGRESS } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True once class and main bow style are set, so a round can be started. */
    val profileReady: StateFlow<Boolean> = profiles.profile.map { p ->
        p.archerClass != null && p.mainBowStyle != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
}

// ---------------- Admin ----------------

/**
 * Admin menu (Admins and the Owner): every account with its role. Only the Owner can switch Admin
 * on or off; the server enforces that too.
 */
class AdminViewModel(private val admin: AdminRepository, accountStore: AccountStore) : ViewModel() {
    val role: StateFlow<AccountRole> = accountStore.role
    /** This account (no reset code for yourself). */
    val myArcherId: String? = accountStore.account.value?.archerId

    private val _resetCode = MutableStateFlow<ResetCode?>(null)
    /** A reset code just made, to show (2.4). */
    val resetCode: StateFlow<ResetCode?> = _resetCode.asStateFlow()
    fun resetCodeShown() { _resetCode.value = null }

    /** Owner / Admin: a one-off password reset code for [archer] (check it's them first). */
    fun makeResetCode(archer: AdminArcher) {
        if (_changing.value != null) return
        _changing.value = archer.archerId
        viewModelScope.launch {
            try {
                _resetCode.value = admin.resetCode(archer.archerId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            } finally {
                _changing.value = null
            }
        }
    }
    val duplicateLocations: StateFlow<Int> = accountStore.duplicateLocations

    private val _archers = MutableStateFlow<Load<List<AdminArcher>>>(Load.Loading)
    val archers: StateFlow<Load<List<AdminArcher>>> = _archers.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Account currently being changed, so only its switch shows as busy. */
    private val _changing = MutableStateFlow<String?>(null)
    val changing: StateFlow<String?> = _changing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() { _message.value = null }

    init { load() }

    fun load(showSpinner: Boolean = false) {
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            _archers.value = try {
                Load.Ready(admin.archers())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_archers.value is Load.Ready) {
                    _message.value = e.friendlyMessage()
                    _archers.value
                } else {
                    Load.Failed(e.friendlyMessage())
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Owner only: switch Admin on or off for [archer]. */
    fun setAdmin(archer: AdminArcher, makeAdmin: Boolean) {
        if (_changing.value != null) return
        _changing.value = archer.archerId
        viewModelScope.launch {
            try {
                admin.setAdmin(archer.archerId, makeAdmin)
                _archers.update { current ->
                    if (current is Load.Ready) {
                        Load.Ready(current.value.map {
                            if (it.archerId == archer.archerId) it.copy(role = if (makeAdmin) AccountRole.ADMIN else AccountRole.USER) else it
                        })
                    } else current
                }
                _message.value = if (makeAdmin) "${archer.username} is now an admin." else "${archer.username} is no longer an admin."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            } finally {
                _changing.value = null
            }
        }
    }
}

/** Admin → Shoot locations: rename, and merge duplicates into the location to keep. */
class AdminLocationsViewModel(
    private val admin: AdminRepository,
    private val locations: LocationRepository,
    accountStore: AccountStore,
) : ViewModel() {
    val role: StateFlow<AccountRole> = accountStore.role

    private val _list = MutableStateFlow<Load<List<AdminLocation>>>(Load.Loading)
    val list: StateFlow<Load<List<AdminLocation>>> = _list.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() { _message.value = null }

    init { load() }

    fun load(showSpinner: Boolean = false) {
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            _list.value = try {
                Load.Ready(admin.locations())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_list.value is Load.Ready) { _message.value = e.friendlyMessage(); _list.value }
                else Load.Failed(e.friendlyMessage())
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun rename(location: AdminLocation, name: String, isPrivate: Boolean, onDone: () -> Unit) = act {
        admin.renameLocation(location.id, name, isPrivate)
        _message.value = "Saved ${name.trim()}" + if (isPrivate) " (private)." else "."
        onDone()
    }

    fun merge(duplicate: AdminLocation, keep: AdminLocation, onDone: () -> Unit) = act {
        val moved = admin.mergeLocation(duplicate.id, keep.id)
        _message.value = "${duplicate.name} merged into ${keep.name} ($moved round${if (moved == 1) "" else "s"} moved)."
        onDone()
    }

    /** Runs a change, updates this phone's own rounds to the new names, then reloads the list. */
    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
                runCatching { locations.refreshNames() }
                load()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            } finally {
                _busy.value = false
            }
        }
    }
}

/** Admin → Possible duplicates: merge one into the other, or approve them as different places. */
class AdminDuplicatesViewModel(
    private val admin: AdminRepository,
    private val locations: LocationRepository,
    accountStore: AccountStore,
) : ViewModel() {
    val role: StateFlow<AccountRole> = accountStore.role

    private val _pairs = MutableStateFlow<Load<List<DuplicatePair>>>(Load.Loading)
    val pairs: StateFlow<Load<List<DuplicatePair>>> = _pairs.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() { _message.value = null }

    init { load() }

    fun load(showSpinner: Boolean = false) {
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            _pairs.value = try {
                Load.Ready(admin.duplicates())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_pairs.value is Load.Ready) { _message.value = e.friendlyMessage(); _pairs.value }
                else Load.Failed(e.friendlyMessage())
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun approve(pair: DuplicatePair) = act {
        admin.approve(pair)
        _message.value = "${pair.a.name} and ${pair.b.name} marked as different places."
    }

    /** Merges [duplicate] into [keep]; its rounds move across. */
    fun merge(duplicate: AdminLocation, keep: AdminLocation) = act {
        val moved = admin.mergeLocation(duplicate.id, keep.id)
        runCatching { locations.refreshNames() }
        _message.value = "${duplicate.name} merged into ${keep.name} ($moved round${if (moved == 1) "" else "s"} moved)."
    }

    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
                load()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            } finally {
                _busy.value = false
            }
        }
    }
}

// ---------------- Shoot location map and page ----------------

/** Map of shoot locations: public ones plus your own private ones; ones you've shot at are highlighted. */
class LocationMapViewModel(
    private val repo: LocationPageRepository,
    accounts: AccountRepository,
    private val locationsRepo: LocationRepository? = null,
) : ViewModel() {
    private val _taken = MutableStateFlow<NearbyLocation?>(null)
    /** Pick mode: the name typed for a new location is already used; offer that one instead. */
    val taken: StateFlow<NearbyLocation?> = _taken.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }
    fun clearTaken() { _taken.value = null }

    /** "New location" on the map screen: a location at a tapped point, then the map reloads. */
    fun createNew(name: String, lat: Double, lon: Double, isPrivate: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.create(name, lat, lon, isPrivate)
                _message.value = "${name.trim()} added" + if (isPrivate) " (private)." else "."
                onDone()
                load()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            }
        }
    }

    /** Pick mode: a new location at a point tapped on the map, given to [roundId]. */
    fun createAt(roundId: String, name: String, lat: Double, lon: Double, isPrivate: Boolean, onDone: () -> Unit) {
        val repo = locationsRepo ?: return
        viewModelScope.launch {
            try {
                repo.nameAt(roundId, name, lat, lon, isPrivate)
                onDone()
            } catch (e: LocationRepository.NameTaken) {
                _taken.value = e.existing
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            }
        }
    }

    /** Pick mode: use the existing location offered after a name clash. */
    fun chooseExisting(roundId: String, location: NearbyLocation, onDone: () -> Unit) {
        val repo = locationsRepo ?: return
        viewModelScope.launch { repo.choose(roundId, location); onDone() }
    }

    /** Pick mode: sets [roundId]'s location to [location] (counts as an edit, so it re-uploads), then [onDone]. */
    fun choose(roundId: String, location: MapLocation, onDone: () -> Unit) {
        val repo = locationsRepo ?: return
        viewModelScope.launch {
            repo.choose(roundId, NearbyLocation(location.id, location.name, -1))
            onDone()
        }
    }

    val signedIn: StateFlow<Boolean> = accounts.account.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), accounts.account.value != null)

    private val _locations = MutableStateFlow<Load<List<MapLocation>>>(Load.Loading)
    val locations: StateFlow<Load<List<MapLocation>>> = _locations.asStateFlow()

    fun load() {
        viewModelScope.launch {
            if (_locations.value !is Load.Ready) _locations.value = Load.Loading
            _locations.value = try {
                Load.Ready(repo.map())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Load.Failed(e.friendlyMessage())
            }
        }
    }
}

/** One location: your rounds there, friends' rounds and stats there, all-archer stats per bow style. Nav arg "locationId". */
class LocationPageViewModel(private val repo: LocationPageRepository, saved: SavedStateHandle, handicap: HandicapRepository? = null) : ViewModel() {
    /** For handicap points of each bow style's averages (null until downloaded). */
    val handicapModel: StateFlow<HandicapModel?> = handicap?.model ?: MutableStateFlow(null)

    private val locationId: String = checkNotNull(saved["locationId"])

    private val _page = MutableStateFlow<Load<LocationPage>>(Load.Loading)
    val page: StateFlow<Load<LocationPage>> = _page.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { load() }

    fun load(showSpinner: Boolean = false) {
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            _page.value = try {
                Load.Ready(repo.page(locationId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Load.Failed(e.friendlyMessage())
            } finally {
                _refreshing.value = false
            }
        }
    }
}

// ---------------- Friends ----------------

/** A class and age group, for the location page's class filter (2.4). */
data class ClassChoice(val archerClass: ArcherClass, val ageGroup: AgeGroup) {
    val label: String get() = if (ageGroup == AgeGroup.ADULT) archerClass.displayName else "${ageGroup.displayName} ${archerClass.displayName}"
}

/** Something loaded from the server: still loading, failed with a message, or ready. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

private fun Throwable.friendlyMessage(): String = when (this) {
    is ApiException -> message
    else -> message ?: "Something went wrong. Try again."
}

/** Friends screen: add by username, incoming / outgoing requests, friends with summaries. */
class FriendsViewModel(
    private val friends: FriendsRepository,
    accounts: AccountRepository,
    handicap: HandicapRepository? = null,
) : ViewModel() {
    /** For each friend's handicap rating on the list (2.4; null until downloaded). */
    val handicapModel: StateFlow<HandicapModel?> = handicap?.model ?: MutableStateFlow(null)

    init { handicap?.let { viewModelScope.launch { it.refreshIfStale() } } }

    val signedIn: StateFlow<Boolean> = accounts.account.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), accounts.account.value != null)

    private val _overview = MutableStateFlow<Load<FriendsOverview>>(Load.Loading)
    val overview: StateFlow<Load<FriendsOverview>> = _overview.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _offers = MutableStateFlow<List<RoundOffer>>(emptyList())
    /** Rounds friends have scored for you, waiting for an answer. */
    val offers: StateFlow<List<RoundOffer>> = _offers.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-off result message for a snackbar (request sent, accepted, errors). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() { _message.value = null }
    fun onUsername(v: String) { _username.value = v.trim() }

    /** Pull to refresh, or reload after a change. */
    fun load(showSpinner: Boolean = false) {
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            _overview.value = try {
                val o = friends.overview()
                // Requests for rounds too (an older server without them just shows none).
                _offers.value = try {
                    friends.offers()
                } catch (e: ApiException) {
                    if (e.status == 404) emptyList() else throw e
                }
                Load.Ready(o)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_overview.value is Load.Ready) {
                    _message.value = e.friendlyMessage()   // keep showing the last good list
                    _overview.value
                } else {
                    Load.Failed(e.friendlyMessage())
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun sendRequest() {
        val name = _username.value
        if (name.isEmpty()) return
        act {
            _message.value = friends.sendRequest(name)
            _username.value = ""
        }
    }

    fun accept(request: FriendRequest) = act {
        friends.accept(request.archerId)
        _message.value = "You and ${request.username} are now friends."
    }

    fun decline(request: FriendRequest) = act {
        friends.remove(request.archerId)
        _message.value = "Request from ${request.username} declined."
    }

    fun cancel(request: FriendRequest) = act {
        friends.remove(request.archerId)
        _message.value = "Request to ${request.username} cancelled."
    }

    fun acceptOffer(offer: RoundOffer) = act {
        friends.acceptOffer(offer.roundId)
        _message.value = "Round from ${offer.fromUsername} added to your history."
    }

    fun declineOffer(offer: RoundOffer) = act {
        friends.declineOffer(offer.roundId)
        _message.value = "Round from ${offer.fromUsername} declined."
    }

    /** Runs a change on the server, then reloads the list. */
    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
                load()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            } finally {
                _busy.value = false
            }
        }
    }
}

/** A friend's finished rounds, newest first. */
class FriendRoundsViewModel(private val friends: FriendsRepository, saved: SavedStateHandle, handicap: HandicapRepository? = null) : ViewModel() {
    /** For handicap points and the friend's rating (null until downloaded). */
    val handicapModel: StateFlow<HandicapModel?> = handicap?.model ?: MutableStateFlow(null)

    val archerId: String = checkNotNull(saved["archerId"])
    val username: String = checkNotNull(saved["username"])

    private val _rounds = MutableStateFlow<Load<List<FriendRoundSummary>>>(Load.Loading)
    val rounds: StateFlow<Load<List<FriendRoundSummary>>> = _rounds.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { load() }

    fun messageShown() { _message.value = null }

    fun load(showSpinner: Boolean = false) {
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            _rounds.value = try {
                Load.Ready(friends.rounds(archerId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Load.Failed(e.friendlyMessage())
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun removeFriend(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                friends.remove(archerId)
                onDone()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            }
        }
    }
}

/** One of a friend's scorecards, read-only. */
class FriendScorecardViewModel(private val friends: FriendsRepository, saved: SavedStateHandle, handicap: HandicapRepository? = null) : ViewModel() {
    /** For the round's handicap points (null until downloaded). */
    val handicapModel: StateFlow<HandicapModel?> = handicap?.model ?: MutableStateFlow(null)

    private val archerId: String = checkNotNull(saved["archerId"])
    private val roundId: String = checkNotNull(saved["roundId"])
    val username: String = checkNotNull(saved["username"])

    private val _round = MutableStateFlow<Load<Round>>(Load.Loading)
    val round: StateFlow<Load<Round>> = _round.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _round.value = Load.Loading
            _round.value = try {
                Load.Ready(friends.round(archerId, roundId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Load.Failed(e.friendlyMessage())
            }
        }
    }
}

/** A round a friend scored for you, to look at before accepting or declining. Nav args "roundId", "username". */
class OfferViewModel(private val friends: FriendsRepository, saved: SavedStateHandle) : ViewModel() {
    private val roundId: String = checkNotNull(saved["roundId"])
    val username: String = checkNotNull(saved["username"])

    private val _round = MutableStateFlow<Load<Round>>(Load.Loading)
    val round: StateFlow<Load<Round>> = _round.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() { _message.value = null }

    init { load() }

    fun load() {
        viewModelScope.launch {
            _round.value = Load.Loading
            _round.value = try {
                Load.Ready(friends.offerRound(roundId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Load.Failed(e.friendlyMessage())
            }
        }
    }

    fun accept(onDone: () -> Unit) = act(onDone) { friends.acceptOffer(roundId) }

    fun decline(onDone: () -> Unit) = act(onDone) { friends.declineOffer(roundId) }

    private fun act(onDone: () -> Unit, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
                onDone()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            } finally {
                _busy.value = false
            }
        }
    }
}

// ---------------- Live groups (2.1) ----------------

/**
 * The lobby of a live group: the join code and QR, who's joined, and (host) Start. Nav arg "groupId".
 * Follows the group every few seconds; when the host starts, every phone goes to scoring.
 */
class LobbyViewModel(
    private val live: LiveGroupRepository,
    private val rounds: RoundRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val groupId: String = checkNotNull(saved["groupId"])

    val poll: StateFlow<LivePoll?> = live.poll(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    /** This phone's round in the group (scoring opens on it). */
    val myRoundId: String? get() = live.myMemberId(groupId)

    fun start() = act { live.start(groupId) }

    /** The host's phone has gone: this phone takes over as host (2.3.01). */
    fun takeOver() = act { live.takeOver(groupId) }

    /** Host: close the group and delete it here. A joined archer: leave it and delete the copy here. */
    fun cancel(onDone: () -> Unit) = act {
        val mine = myRoundId
        if (poll.value?.state?.isHost == true) live.close(groupId) else live.leave(groupId)
        mine?.let { rounds.deleteGroup(it) }
        onDone()
    }

    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                _message.value = e.message
            } finally {
                _busy.value = false
            }
        }
    }
}

data class JoinForm(
    val code: String = "",
    val archerClass: ArcherClass? = null,
    val ageGroup: AgeGroup? = null,
    val bowStyle: BowStyle? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isValid: Boolean get() = code.length == 6 && archerClass != null && ageGroup != null && bowStyle != null
}

/** Join a live group: type or scan the code, check class / age group / bow, join. */
class JoinViewModel(profiles: ProfileRepository, private val live: LiveGroupRepository) : ViewModel() {
    private val _form = MutableStateFlow(JoinForm())
    val form: StateFlow<JoinForm> = _form.asStateFlow()

    init {
        viewModelScope.launch {
            val p = profiles.get()
            _form.update {
                it.copy(
                    archerClass = it.archerClass ?: p.archerClass,
                    ageGroup = it.ageGroup ?: resolveAgeGroup(p.ageGroupManual, p.dateOfBirth, LocalDate.now()),
                    bowStyle = it.bowStyle ?: p.mainBowStyle,
                )
            }
        }
    }

    fun update(change: (JoinForm) -> JoinForm) = _form.update(change)

    /** Typed or scanned text: keeps the code characters (a scanned QR holds more than the code). */
    fun onCode(text: String) = _form.update { it.copy(code = LiveGroupRepository.cleanCode(text), error = null) }

    fun join(onJoined: (String) -> Unit) {
        val f = _form.value
        if (!f.isValid || f.busy) return
        _form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val groupId = live.join(f.code, f.archerClass!!, f.ageGroup!!, f.bowStyle!!)
                _form.update { it.copy(busy = false) }
                onJoined(groupId)
            } catch (e: ApiException) {
                _form.update { it.copy(busy = false, error = e.message) }
            }
        }
    }
}

// ---------------- Profile ----------------

data class RecoveryState(val busy: Boolean = false, val code: String? = null, val error: String? = null)

class ProfileViewModel(
    private val repo: ProfileRepository,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<Profile?>(null)
    val state: StateFlow<Profile?> = _state.asStateFlow()

    /** Signed-in account, or null when using the app offline only. */
    val account: StateFlow<Account?> = accounts.account

    fun signOut() = viewModelScope.launch { accounts.signOut() }

    private val _recovery = MutableStateFlow<RecoveryState>(RecoveryState())
    /** Making a new recovery code (2.4): busy, the code to show, or a problem. */
    val recovery: StateFlow<RecoveryState> = _recovery.asStateFlow()

    fun newRecoveryCode(password: String) {
        if (_recovery.value.busy) return
        _recovery.value = RecoveryState(busy = true)
        viewModelScope.launch {
            _recovery.value = try {
                RecoveryState(code = accounts.newRecoveryCode(password))
            } catch (e: ApiException) {
                RecoveryState(error = e.message)
            }
        }
    }

    fun recoveryDone() { _recovery.value = RecoveryState() }

    private val _deleting = MutableStateFlow(RecoveryState())
    /** Deleting the account (2.4.02): busy, or a problem (code unused). */
    val deleting: StateFlow<RecoveryState> = _deleting.asStateFlow()
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    fun deleteAccount(password: String) {
        if (_deleting.value.busy) return
        _deleting.value = RecoveryState(busy = true)
        viewModelScope.launch {
            _deleting.value = try {
                accounts.deleteAccount(password)
                _deleted.value = true
                RecoveryState()
            } catch (e: ApiException) {
                RecoveryState(error = e.message)
            }
        }
    }

    fun deletedShown() { _deleted.value = false }

    init {
        viewModelScope.launch { _state.value = repo.get() }
    }

    fun update(change: (Profile) -> Profile) = _state.update { it?.let(change) }

    fun save(onDone: () -> Unit) {
        val p = _state.value ?: return
        viewModelScope.launch {
            repo.save(p)
            onDone()
        }
    }
}

// ---------------- Account (sign in / create account) ----------------

enum class AccountStep { USERNAME, EXISTING_USER, NEW_USER, RESET }

data class AccountUiState(
    val step: AccountStep = AccountStep.USERNAME,
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    /** Forgotten password (2.4): the recovery code or a reset code from an Admin. */
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
    /** A recovery code to show once (after creating the account or a reset); the screen closes once it's been seen. */
    val recoveryCode: String? = null,
)

/**
 * Enter a username → the server says whether it exists →
 *  exists: "that username already exists" + password to sign in;
 *  new:    choose a password (typed twice) to create the account.
 */
class AccountViewModel(
    private val accounts: AccountRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        // Pre-fill any username typed into the old profile field.
        viewModelScope.launch {
            val saved = profiles.get().username.orEmpty()
            _state.update { if (it.username.isEmpty()) it.copy(username = saved) else it }
        }
    }

    fun onUsername(v: String) = _state.update { it.copy(username = v.trim(), error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onConfirmPassword(v: String) = _state.update { it.copy(confirmPassword = v, error = null) }
    fun onCode(v: String) = _state.update { it.copy(code = v, error = null) }

    /** "Forgot your password?" (2.4). */
    fun forgotPassword() = _state.update { it.copy(step = AccountStep.RESET, password = "", confirmPassword = "", code = "", error = null) }

    /** The recovery code has been written down. */
    fun recoveryCodeSeen() = _state.update { it.copy(recoveryCode = null) }

    fun resetPassword() {
        val s = _state.value
        if (s.code.isBlank()) {
            _state.update { it.copy(error = "Enter your recovery code (or a reset code from an admin).") }
            return
        }
        AccountRules.newPasswordProblem(s.password, s.confirmPassword)?.let { problem ->
            _state.update { it.copy(error = problem) }
            return
        }
        launchCall {
            val recovery = accounts.resetPassword(s.username, s.code, s.password)
            _state.update { it.copy(signedIn = true, password = "", confirmPassword = "", code = "", recoveryCode = recovery) }
        }
    }

    /** Back to the username step (e.g. "use a different username"). */
    fun changeUsername() = _state.update {
        it.copy(step = AccountStep.USERNAME, password = "", confirmPassword = "", error = null)
    }

    fun continueWithUsername() {
        val username = _state.value.username
        if (!AccountRules.isValidUsername(username)) {
            _state.update { it.copy(error = AccountRules.USERNAME_HELP) }
            return
        }
        launchCall {
            val check = accounts.checkUsername(username)
            _state.update {
                it.copy(
                    username = check.username,
                    step = if (check.exists) AccountStep.EXISTING_USER else AccountStep.NEW_USER,
                )
            }
        }
    }

    fun signIn() {
        val s = _state.value
        if (s.password.isEmpty()) {
            _state.update { it.copy(error = "Enter your password.") }
            return
        }
        launchCall {
            accounts.login(s.username, s.password)
            _state.update { it.copy(signedIn = true, password = "") }
        }
    }

    fun createAccount() {
        val s = _state.value
        AccountRules.newPasswordProblem(s.password, s.confirmPassword)?.let { problem ->
            _state.update { it.copy(error = problem) }
            return
        }
        launchCall {
            try {
                val recovery = accounts.register(s.username, s.password)
                _state.update { it.copy(signedIn = true, password = "", confirmPassword = "", recoveryCode = recovery) }
            } catch (e: ApiException) {
                if (e.code == "username_taken") {
                    // Someone took it in the meantime: switch to signing in.
                    _state.update { it.copy(step = AccountStep.EXISTING_USER, confirmPassword = "") }
                }
                throw e
            }
        }
    }

    /** Runs a server call with a busy spinner, turning failures into a message on screen. */
    private fun launchCall(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

// ---------------- New round ----------------

data class NewRoundForm(
    val shootDate: LocalDate = LocalDate.now(),
    val targetCountText: String = "",
    val archerClass: ArcherClass? = null,
    /** Manual choice for this round; null = use the profile (manual or from date of birth). */
    val ageGroupChoice: AgeGroup? = null,
    val bowStyle: BowStyle? = null,
    val profile: Profile = Profile(),
    /** Group round scored on this phone (app 2.0): you plus [members]. */
    val group: Boolean = false,
    val members: List<GroupMemberDraft> = emptyList(),
    /** Live group (app 2.1): others join on their own phones with a code (no signal: phone to phone, 2.3). */
    val live: Boolean = false,
    val liveMethod: LiveMethod = LiveMethod.SINGLE,
    /** Live group: how many archers in all, including you and anyone added here (Start waits for them, 2.3.01). */
    val liveExpectedText: String = "",
    val starting: Boolean = false,
    val error: String? = null,
) {
    val targetCount: Int? get() = targetCountText.toIntOrNull()?.takeIf { it in 1..RoundRepository.MAX_TARGETS }

    /** At least you and whoever's been added here; at most the group limit. */
    val liveExpectedRange: IntRange get() = (members.size + 1)..RoundRepository.MAX_GROUP
    val liveExpected: Int? get() = liveExpectedText.toIntOrNull()?.takeIf { it in liveExpectedRange }

    /** Room for more archers (the limit includes you). */
    val canAddMember: Boolean get() = members.size + 1 < RoundRepository.MAX_GROUP

    /** Age group from the profile for the shoot date. */
    val profileAgeGroup: AgeGroup?
        get() = resolveAgeGroup(profile.ageGroupManual, profile.dateOfBirth, shootDate)

    val ageGroup: AgeGroup? get() = ageGroupChoice ?: profileAgeGroup

    val ageGroupSource: String
        get() = when {
            ageGroupChoice != null -> "chosen for this round"
            profile.ageGroupManual != null -> "from profile"
            profile.dateOfBirth != null -> "from date of birth"
            else -> "not set"
        }

    val isValid: Boolean
        get() = targetCount != null && archerClass != null && ageGroup != null && bowStyle != null &&
            (!group || live || members.isNotEmpty()) && (!live || liveExpected != null)
}

/** Someone being added to a group round: a friend (from your friends list) or a guest (name only). */
data class GroupMemberDraft(
    val key: String = java.util.UUID.randomUUID().toString(),
    val role: GroupRole,
    val name: String,
    val archerId: String? = null,
    val archerClass: ArcherClass,
    val ageGroup: AgeGroup,
    val bowStyle: BowStyle,
)

class NewRoundViewModel(
    private val profiles: ProfileRepository,
    private val rounds: RoundRepository,
    private val locations: LocationRepository,
    private val type: RoundType = RoundType.BIG_GAME,
    private val accountStore: AccountStore? = null,
    private val live: LiveGroupRepository? = null,
) : ViewModel() {
    private val _form = MutableStateFlow<NewRoundForm?>(null)
    val form: StateFlow<NewRoundForm?> = _form.asStateFlow()

    /** Signed in: friends can be added to a group (else guests only). */
    val signedIn: Boolean get() = accountStore?.account?.value != null

    /** Your friends as last fetched (works with no signal), for adding to a group. */
    fun friends(): List<CachedFriend> = accountStore?.cachedFriends().orEmpty().sortedBy { it.username.lowercase() }

    fun addMember(m: GroupMemberDraft) = update { f ->
        if (!f.canAddMember || (m.archerId != null && f.members.any { it.archerId == m.archerId })) f
        else f.copy(members = f.members + m)
    }

    fun replaceMember(m: GroupMemberDraft) = update { f -> f.copy(members = f.members.map { if (it.key == m.key) m else it }) }

    fun removeMember(key: String) = update { f -> f.copy(members = f.members.filterNot { it.key == key }) }

    /** Scoring order: move someone up or down the list (you're always first). */
    fun moveMember(key: String, by: Int) = update { f ->
        val i = f.members.indexOfFirst { it.key == key }
        val j = i + by
        if (i < 0 || j !in f.members.indices) f
        else f.copy(members = f.members.toMutableList().apply { add(j, removeAt(i)) })
    }

    init {
        // Follow profile changes (e.g. after "Edit profile"), filling anything not yet chosen.
        viewModelScope.launch {
            profiles.profile.collect { p ->
                _form.update { f ->
                    (f ?: NewRoundForm()).let {
                        it.copy(
                            profile = p,
                            archerClass = it.archerClass ?: p.archerClass,
                            bowStyle = it.bowStyle ?: p.mainBowStyle,
                        )
                    }
                }
            }
        }
    }

    fun update(change: (NewRoundForm) -> NewRoundForm) = _form.update { it?.let(change) }

    /**
     * Live group: makes the group on this phone (with a join code), then [onLive] opens the lobby. With no signal the
     * others join phone to phone and the server is told later (2.3); nothing is kept if the server turns the group down.
     */
    fun startLive(onLive: (String) -> Unit) {
        val f = _form.value ?: return
        val repo = live ?: return
        if (!f.isValid || f.starting) return
        update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            val groupId = java.util.UUID.randomUUID().toString()
            val ownId = rounds.createGroupRound(
                f.shootDate, f.targetCount!!, f.archerClass!!, f.ageGroup!!, f.bowStyle!!, type,
                f.members.map { NewGroupMember(it.role, it.name, it.archerId, it.archerClass, it.ageGroup, it.bowStyle) },
                groupId = groupId, live = true,
            )
            try {
                repo.create(groupId, f.liveMethod, f.liveExpected ?: 0)
                locations.captureFor(ownId)
                update { it.copy(starting = false) }
                onLive(groupId)
            } catch (e: ApiException) {
                rounds.deleteGroup(ownId)
                update { it.copy(starting = false, error = e.message) }
            }
        }
    }

    fun start(onCreated: (String) -> Unit) {
        val f = _form.value ?: return
        if (!f.isValid) return
        viewModelScope.launch {
            val id = if (f.group) {
                rounds.createGroupRound(
                    f.shootDate, f.targetCount!!, f.archerClass!!, f.ageGroup!!, f.bowStyle!!, type,
                    f.members.map { NewGroupMember(it.role, it.name, it.archerId, it.archerClass, it.ageGroup, it.bowStyle) },
                )
            } else {
                rounds.createRound(f.shootDate, f.targetCount!!, f.archerClass!!, f.ageGroup!!, f.bowStyle!!, type)
            }
            // GPS fix in the background (if allowed); scoring starts straight away.
            locations.captureFor(id)
            onCreated(id)
        }
    }
}

// ---------------- Shoot location (scoring screen and scorecard) ----------------

/**
 * The location sheet for one of this archer's rounds: "Not here?" list, naming a new location, or
 * taking a position now for a round that has none. Nav argument "roundId".
 */
class RoundLocationViewModel(
    private val locations: LocationRepository,
    accounts: AccountRepository,
    saved: SavedStateHandle,
    private val pages: LocationPageRepository? = null,
) : ViewModel() {
    private val roundId: String = checkNotNull(saved["roundId"])

    private val _known = MutableStateFlow<List<MapLocation>>(emptyList())
    /** Every location you can use (public ones and your own private ones), by name, for the drop-down. */
    val known: StateFlow<List<MapLocation>> = _known.asStateFlow()

    fun chooseKnown(location: MapLocation, onDone: () -> Unit) =
        choose(NearbyLocation(location.id, location.name, -1), onDone)

    val signedIn: StateFlow<Boolean> = accounts.account.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), accounts.account.value != null)

    private val _nearby = MutableStateFlow<Load<List<NearbyLocation>>>(Load.Loading)
    val nearby: StateFlow<Load<List<NearbyLocation>>> = _nearby.asStateFlow()

    private val _hasFix = MutableStateFlow(true)
    /** False when the round has no GPS position, so the sheet offers "Get position now". */
    val hasFix: StateFlow<Boolean> = _hasFix.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Set when the name typed is already used: offer that location instead. */
    private val _taken = MutableStateFlow<NearbyLocation?>(null)
    val taken: StateFlow<NearbyLocation?> = _taken.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() { _message.value = null }

    val hasPermission: Boolean get() = locations.hasPermission()

    /** Called when the sheet opens. */
    fun load() {
        _taken.value = null
        pages?.let { repo ->
            viewModelScope.launch {
                runCatching { repo.map() }.onSuccess { list -> _known.value = list.sortedBy { it.name.lowercase() } }
            }
        }
        viewModelScope.launch {
            _hasFix.value = locations.hasFix(roundId)
            if (!_hasFix.value) { _nearby.value = Load.Ready(emptyList()); return@launch }
            _nearby.value = Load.Loading
            _nearby.value = try {
                Load.Ready(locations.nearby(roundId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Load.Failed(e.friendlyMessage())
            }
        }
    }

    fun captureNow() = act {
        if (locations.captureNow(roundId)) load()
        else _message.value = "Couldn't get a GPS position. Check location is on and try again outside."
    }

    fun choose(location: NearbyLocation, onDone: () -> Unit) = act {
        locations.choose(roundId, location)
        onDone()
    }

    fun name(name: String, isPrivate: Boolean, onDone: () -> Unit) = act {
        try {
            locations.name(roundId, name, isPrivate)
            onDone()
        } catch (e: LocationRepository.NameTaken) {
            _taken.value = e.existing
        }
    }

    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = e.friendlyMessage()
            } finally {
                _busy.value = false
            }
        }
    }
}

// ---------------- Edit round details ----------------

data class EditRoundForm(
    val shootDate: LocalDate,
    val targetCountText: String,
    val archerClass: ArcherClass,
    val ageGroup: AgeGroup,
    val bowStyle: BowStyle,
    /** As the round is now, to warn about removing scored targets. */
    val original: Round,
    /** In a group: whose round this is ("You", a friend or a guest); null when it's not a group round. */
    val groupName: String? = null,
) {
    val targetCount: Int? get() = targetCountText.toIntOrNull()?.takeIf { it in 1..RoundRepository.MAX_TARGETS }

    /** Scored targets that a lower target count would remove. */
    val scoredRemoved: List<Int>
        get() = targetCount?.let { n -> original.targets.filter { it.targetNo > n && it.isComplete }.map { it.targetNo } }.orEmpty()

    /** More targets on a finished round reopens it. */
    val reopens: Boolean get() = (targetCount ?: 0) > original.targetCount && original.status == RoundStatus.COMPLETED

    val changed: Boolean
        get() = shootDate != original.shootDate || targetCount != original.targetCount || archerClass != original.archerClass ||
            ageGroup != original.ageGroup || bowStyle != original.bowStyle
}

/** Edit a round's details: date, number of targets, class, age group, bow style. Nav arg "roundId". */
class EditRoundViewModel(private val rounds: RoundRepository, saved: SavedStateHandle) : ViewModel() {
    private val roundId: String = checkNotNull(saved["roundId"])

    private val _form = MutableStateFlow<EditRoundForm?>(null)
    val form: StateFlow<EditRoundForm?> = _form.asStateFlow()

    init {
        viewModelScope.launch {
            val r = rounds.round(roundId).first { it != null }!!
            val member = rounds.group(roundId).first().firstOrNull { it.roundId == roundId }
            _form.value = EditRoundForm(r.shootDate, r.targetCount.toString(), r.archerClass, r.ageGroup, r.bowStyle, r, member?.name)
        }
    }

    fun update(change: (EditRoundForm) -> EditRoundForm) = _form.update { it?.let(change) }

    fun save(onDone: () -> Unit) {
        val f = _form.value ?: return
        val count = f.targetCount ?: return
        viewModelScope.launch {
            rounds.updateDetails(roundId, f.shootDate, count, f.archerClass, f.ageGroup, f.bowStyle)
            onDone()
        }
    }
}

// ---------------- Scoring ----------------

/** A check by hand (2.3): five targets to compare aloud with the other scorer, card by card. */
data class ManualCheck(val targets: List<Int>, val cards: List<Card>, val others: List<String>) {
    /** [points] for each of [targets] (null: not entered here for this card). */
    data class Card(val memberId: String, val name: String, val points: List<Int?>) {
        val total: Int get() = points.sumOf { it ?: 0 }
    }
}

class ScoringViewModel(
    private val rounds: RoundRepository,
    private val saved: SavedStateHandle,
    private val liveRepo: LiveGroupRepository? = null,
) : ViewModel() {
    /** The round opened from the nav route (in a group, whoever was tapped; usually the scorer's own round). */
    val startRoundId: String = checkNotNull(saved["roundId"])

    /** The round being scored now: in a group this moves from archer to archer. */
    private val _roundId = MutableStateFlow(saved.get<String>(KEY_CURRENT) ?: startRoundId)
    val roundId: StateFlow<String> = _roundId.asStateFlow()

    /** Everyone in the group, in scoring order (empty when it's not a group round). */
    val group: StateFlow<List<GroupMember>> = rounds.group(startRoundId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Live groups (2.1): scores go to the server, other phones' scores come back every few seconds ----

    private var liveGroupId: String? = null
    private val _live = MutableStateFlow<LivePoll?>(null)
    /** The live group, if this is one (null otherwise). */
    val live: StateFlow<LivePoll?> = _live.asStateFlow()

    /** Double / 3+ scorers: this phone's own entries by (target, card), shown until both scorers match. */
    private val _mineHere = MutableStateFlow<Map<Pair<Int, String>, TargetScore>>(emptyMap())

    /**
     * The round on screen. Double / 3+ scorers: for a card this phone scores, its own entries are shown on top of the
     * saved (matched) scores, so you see what you entered while waiting for the other scorer.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val round: StateFlow<Round?> = combine(_roundId.flatMapLatest { rounds.round(it) }, _mineHere, _live) { r, mine, poll ->
        val s = poll?.state
        if (r == null || s == null || !s.checked || !s.canScore(r.id)) return@combine r
        val entered = mine.filterKeys { it.second == r.id }.mapKeys { it.key.first }
        if (entered.isEmpty()) r
        else r.copy(targets = (r.targets.associateBy { it.targetNo } + entered).values.sortedBy { it.targetNo })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Double / 3+ scorers: where the card on screen has got to on this target (waiting, doesn't match, saved). */
    fun checkText(): String? {
        val s = _live.value?.state ?: return null
        val n = _targetNo.value
        val id = _roundId.value
        return s.checkText(n, id, iEntered = s.canScore(id) && _mineHere.value.containsKey(n to id))
    }

    /** True when the two scorers' entries for the card on screen differ. */
    fun checkMismatch(): Boolean = _live.value?.state?.cells?.get(_targetNo.value to _roundId.value) == "MISMATCH"

    /** Targets put off with "Not now" (asked again once the next target is scored). */
    private val _snoozed = MutableStateFlow<Pair<List<Int>, Int>?>(null)

    /**
     * Double / 3+ scorers with no way to the other scorer (2.3, Pete): every five targets entered here that haven't been
     * compared, a prompt to compare them by hand. Null when none is due.
     */
    val manualCheck: StateFlow<ManualCheck?> = combine(_live, _snoozed) { poll, snoozed ->
        val s = poll?.state ?: return@combine null
        if (poll.inTouch) return@combine null
        val mine = s.entries.count { it.scorerMemberId == s.myMemberId }
        val due = s.dueManualCheck().takeIf { it.isNotEmpty() } ?: return@combine null
        if (snoozed != null && snoozed.first == due && snoozed.second >= mine) return@combine null
        val waiting = s.myWaiting(due)
        if (waiting.isEmpty()) return@combine null
        val cards = waiting.keys.map { it.second }.distinct().sortedBy { s.member(it)?.sortOrder ?: 0 }
        ManualCheck(
            targets = due,
            cards = cards.map { card ->
                val name = s.member(card)?.let { if (it.memberId == s.myMemberId) "${it.name} (you)" else it.name } ?: "?"
                ManualCheck.Card(card, name, due.map { waiting[it to card] })
            },
            others = s.otherScorerNames(cards),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The scorers agree on the targets in the prompt: saved here (compared again when the other phone is back). */
    fun manualCheckAgreed() {
        val c = manualCheck.value ?: return
        val gid = liveGroupId ?: return
        val cells = c.cards.flatMap { card -> c.targets.filterIndexed { i, _ -> card.points[i] != null }.map { it to card.memberId } }.toSet()
        viewModelScope.launch { liveRepo?.markChecked(gid, cells) }
    }

    /** "Not now": asked again after the next target. */
    fun manualCheckLater() {
        val c = manualCheck.value ?: return
        val s = _live.value?.state ?: return
        _snoozed.value = c.targets to s.entries.count { it.scorerMemberId == s.myMemberId }
    }

    private val _message = MutableStateFlow<String?>(null)
    /** One-off note, e.g. "Dave is scoring this card". */
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    /** Was the target on screen scored the last time the group changed? (A change to scored moves everyone on.) */
    private var viewedWasComplete: Boolean? = null

    /**
     * Live groups move on automatically: when the archer on screen has just been scored on this target, go to the next
     * one still to score (by target, then scoring order), on every phone.
     */
    private fun followGroup(members: List<GroupMember>) {
        val viewing = members.firstOrNull { it.roundId == _roundId.value } ?: return
        val n = _targetNo.value
        if (n == 0) return
        val complete = viewing.round.target(n).isComplete
        val justScored = viewedWasComplete == false && complete
        viewedWasComplete = complete
        if (!justScored) return
        val targets = members.maxOfOrNull { it.round.targetCount } ?: return
        for (t in 1..targets) {
            val next = members.firstOrNull { t <= it.round.targetCount && !it.round.target(t).isComplete } ?: continue
            setRound(next.roundId)
            setTarget(t)
            viewedWasComplete = false
            return
        }
    }

    /** In a live group: can this phone score the card on screen? (Always true otherwise.) */
    fun canScoreHere(): Boolean {
        val s = _live.value?.state ?: return liveGroupId == null
        return s.canScore(_roundId.value)
    }

    /** Why this card can't be scored here, for the banner. */
    fun whyNotHere(): String? {
        if (liveGroupId == null) return null
        val s = _live.value?.state ?: return "Connecting to the group…"
        return when {
            s.closed -> "The group is closed."
            !s.started -> "Waiting for the host to start."
            s.canScore(_roundId.value) -> null
            else -> s.scorerNames(_roundId.value).takeIf { it.isNotEmpty() }?.joinToString(" and ")
                ?.let { "$it ${if (it.contains(" and ")) "score" else "scores"} this card." } ?: "Someone else scores this card."
        }
    }

    /** Restored target, else the one asked for in the nav route (e.g. "Edit target 7" on the scorecard). */
    private val _targetNo = MutableStateFlow(saved.get<Int>(KEY_TARGET) ?: saved.get<Int>(ARG_START_TARGET) ?: 0)

    /** Current target number; 0 until the round has loaded and we pick the first unscored target. */
    val targetNo: StateFlow<Int> = _targetNo.asStateFlow()

    init {
        viewModelScope.launch {
            val gid = rounds.groupIdOf(startRoundId) ?: return@launch
            if (liveRepo?.isLive(gid) != true) return@launch
            liveGroupId = gid
            launch { liveRepo.poll(gid).collect { _live.value = it } }
            launch { liveRepo.myEntries(gid).collect { _mineHere.value = it } }
            group.collect { members -> followGroup(members) }
        }
    }


    fun ensureTargetSelected(r: Round) {
        if (_targetNo.value == 0 || !r.canGoTo(_targetNo.value)) {
            setTarget(r.firstUnscoredTargetNo ?: 1)
        }
    }

    /** Opens a target, unless it's ahead of the next unscored one (no skipping ahead). */
    fun goTo(n: Int) {
        val r = round.value ?: return
        if (r.canGoTo(n)) setTarget(n)
    }

    private fun setTarget(n: Int) {
        _targetNo.value = n
        saved[KEY_TARGET] = n
    }

    private fun setRound(id: String) {
        _roundId.value = id
        saved[KEY_CURRENT] = id
    }

    /** Group: score someone else on this target (or their next unscored one if they're behind). */
    fun selectMember(m: GroupMember) {
        val n = _targetNo.value
        setRound(m.roundId)
        setTarget(if (m.round.canGoTo(n)) n else m.round.firstUnscoredTargetNo ?: n)
    }

    /** Where Next goes in a group: the next archer on this target, or the first archer on the next target. */
    fun nextInGroup(): Pair<GroupMember, Int>? {
        val g = group.value.takeIf { it.size > 1 } ?: return null
        val i = g.indexOfFirst { it.roundId == _roundId.value }.takeIf { it >= 0 } ?: return null
        val n = _targetNo.value
        return if (i < g.lastIndex) g[i + 1] to n else g.first() to n + 1
    }

    /** Where Prev goes in a group: the archer before on this target, or the last archer on the target before. */
    fun prevInGroup(): Pair<GroupMember, Int>? {
        val g = group.value.takeIf { it.size > 1 } ?: return null
        val i = g.indexOfFirst { it.roundId == _roundId.value }.takeIf { it >= 0 } ?: return null
        val n = _targetNo.value
        return if (i > 0) g[i - 1] to n else g.last() to n - 1
    }

    fun next() {
        val r = round.value ?: return
        val step = nextInGroup()
        if (step == null) { goTo(_targetNo.value + 1); return }
        val (m, n) = step
        if (n > r.targetCount) return
        setRound(m.roundId)
        setTarget(n)
    }

    fun prev() {
        val step = prevInGroup()
        if (step == null) { goTo(_targetNo.value - 1); return }
        val (m, n) = step
        if (n < 1) return
        setRound(m.roundId)
        setTarget(n)
    }

    private fun edit(change: (TargetScore) -> TargetScore) {
        val r = round.value ?: return
        val id = _roundId.value
        if (!canScoreHere()) {
            _message.value = whyNotHere()
            return
        }
        val current = r.target(_targetNo.value)
        val updated = runCatching { change(current) }.getOrNull() ?: return
        // Double / 3+ scorers: the round only gets the score once both scorers match (the poll copies it in).
        val checked = _live.value?.state?.checked == true
        viewModelScope.launch {
            if (!checked) rounds.saveTarget(id, updated)
            liveGroupId?.let { liveRepo?.send(it, id, updated) }
        }
    }

    /** In a group, everyone not yet scored on this target gets the same target type. */
    fun setType(type: TargetType, fanAnimals: Int = TargetScore.MIN_FAN) {
        edit { it.withType(type, fanAnimals) }
        // Live groups only copy the type to others when this phone scores everyone (one scorer).
        val s = _live.value?.state
        val scoresAll = s == null || (!s.checked && s.members.all { s.canScore(it.memberId) })
        if (group.value.size > 1 && scoresAll && canScoreHere()) {
            val id = _roundId.value
            val n = _targetNo.value
            viewModelScope.launch { rounds.setTypeForGroup(id, n, type, fanAnimals) }
        }
    }

    /** Tapping the selected result again clears it. */
    fun toggleResult(animalIndex: Int, result: AnimalResult) = edit { t ->
        val next = if (t.animals[animalIndex] == result) AnimalResult.Pending else result
        t.withAnimalResult(animalIndex, next)
    }

    fun setOverride(points: Int?) = edit { it.withOverride(points) }

    /** Woodsman: the next arrow (wound, kill or miss). */
    fun addArrow(arrow: WoodsmanArrow) = edit { it.withArrow(arrow) }

    /** Woodsman: takes back the last arrow. */
    fun undoArrow() = edit { it.withoutLastArrow() }

    fun clearTarget() = edit { it.cleared() }

    /** Finishes the round (in a group, everyone's). A live group's host also closes it for every phone. */
    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        val gid = liveGroupId
        if (gid != null && _live.value?.state?.isHost == true) {
            try {
                liveRepo?.close(gid)
            } catch (e: ApiException) {
                _message.value = "Couldn't close the group (${e.message}). Your rounds are finished here."
            }
        }
        rounds.setGroupStatus(_roundId.value, RoundStatus.COMPLETED)
        onDone()
    }

    fun reopen() = viewModelScope.launch { rounds.setGroupStatus(_roundId.value, RoundStatus.IN_PROGRESS) }

    /** Live group: the host's phone has gone, so this phone takes over as host (2.3.01). */
    fun takeOver() {
        val gid = liveGroupId ?: return
        viewModelScope.launch {
            try {
                liveRepo?.takeOver(gid)
                _message.value = "You're the host now. Other phones follow yours when they're in range or have signal."
            } catch (e: ApiException) {
                _message.value = e.message
            }
        }
    }

    /** Deletes the round (in a group, everyone's). A live group: the host closes it; others leave (and can join again). */
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val gid = liveGroupId
        if (gid != null && liveRepo != null) {
            runCatching { if (_live.value?.state?.isHost == true) liveRepo.close(gid) else liveRepo.leave(gid) }
        }
        rounds.deleteGroup(_roundId.value)
        onDone()
    }

    companion object {
        private const val KEY_TARGET = "targetNo"
        private const val KEY_CURRENT = "currentRoundId"

        /** Optional nav argument: target to open first (0 = next unscored). */
        const val ARG_START_TARGET = "target"
    }
}

// ---------------- Scorecard (end of round / reviewing a past round) ----------------

class ScorecardViewModel(
    private val rounds: RoundRepository,
    saved: SavedStateHandle,
    handicap: HandicapRepository? = null,
) : ViewModel() {
    /** For the round's handicap points (null until downloaded). */
    val handicapModel: StateFlow<HandicapModel?> = handicap?.model ?: MutableStateFlow(null)

    val roundId: String = checkNotNull(saved["roundId"])

    val round: StateFlow<Round?> =
        rounds.round(roundId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Everyone in the group, for the group scorecard (empty when it's not a group round). */
    val group: StateFlow<List<GroupMember>> = rounds.group(roundId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A new personal best (your own finished rounds only): this round and the one it beat. */
    val personalBest: StateFlow<Pair<Round, Round>?> = rounds.finishedRounds.map { all ->
        val me = all.firstOrNull { it.id == roundId } ?: return@map null
        if (me.id !in PersonalStats.personalBestIds(all)) return@map null
        PersonalStats.previousBest(me, all)?.let { me to it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reopen() = viewModelScope.launch { rounds.setGroupStatus(roundId, RoundStatus.IN_PROGRESS) }

    /** Deletes the round; in a group, everyone's. */
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        rounds.deleteGroup(roundId)
        onDone()
    }
}
