package uk.co.fnurble.nfas.ui

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.fnurble.nfas.AppContainer
import uk.co.fnurble.nfas.NfasApp
import uk.co.fnurble.nfas.data.Account
import uk.co.fnurble.nfas.data.AccountStore
import uk.co.fnurble.nfas.data.AdminArcher
import uk.co.fnurble.nfas.data.AdminLocation
import uk.co.fnurble.nfas.data.DuplicatePair
import uk.co.fnurble.nfas.data.AdminRepository
import uk.co.fnurble.nfas.data.FriendRequest
import uk.co.fnurble.nfas.data.FriendRoundSummary
import uk.co.fnurble.nfas.data.FriendsOverview
import uk.co.fnurble.nfas.data.FriendsRepository
import uk.co.fnurble.nfas.data.LocationPage
import uk.co.fnurble.nfas.data.LocationPageRepository
import uk.co.fnurble.nfas.data.LocationRepository
import uk.co.fnurble.nfas.data.MapLocation
import uk.co.fnurble.nfas.data.NetworkStatus
import uk.co.fnurble.nfas.data.ServerState
import uk.co.fnurble.nfas.data.AccountRepository
import uk.co.fnurble.nfas.data.Profile
import uk.co.fnurble.nfas.data.ProfileRepository
import uk.co.fnurble.nfas.data.RoundRepository
import uk.co.fnurble.nfas.data.RoundSummary
import uk.co.fnurble.nfas.data.remote.ApiException
import uk.co.fnurble.nfas.data.sync.RoundSyncer
import uk.co.fnurble.nfas.domain.AccountRole
import uk.co.fnurble.nfas.domain.AccountRules
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.AnimalResult
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.domain.NearbyLocation
import uk.co.fnurble.nfas.domain.Round
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.domain.TargetScore
import uk.co.fnurble.nfas.domain.TargetType
import uk.co.fnurble.nfas.domain.resolveAgeGroup
import java.time.Instant
import java.time.LocalDate

/** Creates a ViewModel with access to the app container and the nav arguments. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM = viewModel(
    factory = viewModelFactory {
        initializer {
            val app = this[APPLICATION_KEY] as NfasApp
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
) : SyncingViewModel(syncer, accountStore, accounts) {
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
}

/** Score history: finished rounds only, newest first. */
class HistoryViewModel(
    roundRepo: RoundRepository,
    accounts: AccountRepository,
    syncer: RoundSyncer,
    accountStore: AccountStore,
) : SyncingViewModel(syncer, accountStore, accounts) {
    val rounds: StateFlow<List<RoundSummary>?> = roundRepo.rounds
        .map { list -> list.filter { it.status == RoundStatus.COMPLETED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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
class LocationPageViewModel(private val repo: LocationPageRepository, saved: SavedStateHandle) : ViewModel() {
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
class FriendsViewModel(private val friends: FriendsRepository, accounts: AccountRepository) : ViewModel() {
    val signedIn: StateFlow<Boolean> = accounts.account.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), accounts.account.value != null)

    private val _overview = MutableStateFlow<Load<FriendsOverview>>(Load.Loading)
    val overview: StateFlow<Load<FriendsOverview>> = _overview.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

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
                Load.Ready(friends.overview())
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
class FriendRoundsViewModel(private val friends: FriendsRepository, saved: SavedStateHandle) : ViewModel() {
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
class FriendScorecardViewModel(private val friends: FriendsRepository, saved: SavedStateHandle) : ViewModel() {
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

// ---------------- Profile ----------------

class ProfileViewModel(
    private val repo: ProfileRepository,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<Profile?>(null)
    val state: StateFlow<Profile?> = _state.asStateFlow()

    /** Signed-in account, or null when using the app offline only. */
    val account: StateFlow<Account?> = accounts.account

    fun signOut() = viewModelScope.launch { accounts.signOut() }

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

enum class AccountStep { USERNAME, EXISTING_USER, NEW_USER }

data class AccountUiState(
    val step: AccountStep = AccountStep.USERNAME,
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
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
                accounts.register(s.username, s.password)
                _state.update { it.copy(signedIn = true, password = "", confirmPassword = "") }
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
) {
    val targetCount: Int? get() = targetCountText.toIntOrNull()?.takeIf { it in 1..RoundRepository.MAX_TARGETS }

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

    val isValid: Boolean get() = targetCount != null && archerClass != null && ageGroup != null && bowStyle != null
}

class NewRoundViewModel(
    private val profiles: ProfileRepository,
    private val rounds: RoundRepository,
    private val locations: LocationRepository,
) : ViewModel() {
    private val _form = MutableStateFlow<NewRoundForm?>(null)
    val form: StateFlow<NewRoundForm?> = _form.asStateFlow()

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

    fun start(onCreated: (String) -> Unit) {
        val f = _form.value ?: return
        if (!f.isValid) return
        viewModelScope.launch {
            val id = rounds.createRound(f.shootDate, f.targetCount!!, f.archerClass!!, f.ageGroup!!, f.bowStyle!!)
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
            _form.value = EditRoundForm(r.shootDate, r.targetCount.toString(), r.archerClass, r.ageGroup, r.bowStyle, r)
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

class ScoringViewModel(
    private val rounds: RoundRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val roundId: String = checkNotNull(saved["roundId"])

    val round: StateFlow<Round?> =
        rounds.round(roundId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Restored target, else the one asked for in the nav route (e.g. "Edit target 7" on the scorecard). */
    private val _targetNo = MutableStateFlow(saved.get<Int>(KEY_TARGET) ?: saved.get<Int>(ARG_START_TARGET) ?: 0)

    /** Current target number; 0 until the round has loaded and we pick the first unscored target. */
    val targetNo: StateFlow<Int> = _targetNo.asStateFlow()

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

    private fun edit(change: (TargetScore) -> TargetScore) {
        val r = round.value ?: return
        val current = r.target(_targetNo.value)
        val updated = runCatching { change(current) }.getOrNull() ?: return
        viewModelScope.launch { rounds.saveTarget(roundId, updated) }
    }

    fun setType(type: TargetType, fanAnimals: Int = TargetScore.MIN_FAN) = edit { it.withType(type, fanAnimals) }

    /** Tapping the selected result again clears it. */
    fun toggleResult(animalIndex: Int, result: AnimalResult) = edit { t ->
        val next = if (t.animals[animalIndex] == result) AnimalResult.Pending else result
        t.withAnimalResult(animalIndex, next)
    }

    fun setOverride(points: Int?) = edit { it.withOverride(points) }

    fun clearTarget() = edit { it.cleared() }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        rounds.setStatus(roundId, RoundStatus.COMPLETED)
        onDone()
    }

    fun reopen() = viewModelScope.launch { rounds.setStatus(roundId, RoundStatus.IN_PROGRESS) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        rounds.delete(roundId)
        onDone()
    }

    companion object {
        private const val KEY_TARGET = "targetNo"

        /** Optional nav argument: target to open first (0 = next unscored). */
        const val ARG_START_TARGET = "target"
    }
}

// ---------------- Scorecard (end of round / reviewing a past round) ----------------

class ScorecardViewModel(
    private val rounds: RoundRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val roundId: String = checkNotNull(saved["roundId"])

    val round: StateFlow<Round?> =
        rounds.round(roundId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reopen() = viewModelScope.launch { rounds.setStatus(roundId, RoundStatus.IN_PROGRESS) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        rounds.delete(roundId)
        onDone()
    }
}
