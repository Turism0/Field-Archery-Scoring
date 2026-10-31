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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.fnurble.nfas.AppContainer
import uk.co.fnurble.nfas.NfasApp
import uk.co.fnurble.nfas.data.Account
import uk.co.fnurble.nfas.data.AccountStore
import uk.co.fnurble.nfas.data.FriendRequest
import uk.co.fnurble.nfas.data.FriendRoundSummary
import uk.co.fnurble.nfas.data.FriendsOverview
import uk.co.fnurble.nfas.data.FriendsRepository
import uk.co.fnurble.nfas.data.AccountRepository
import uk.co.fnurble.nfas.data.Profile
import uk.co.fnurble.nfas.data.ProfileRepository
import uk.co.fnurble.nfas.data.RoundRepository
import uk.co.fnurble.nfas.data.RoundSummary
import uk.co.fnurble.nfas.data.remote.ApiException
import uk.co.fnurble.nfas.data.sync.RoundSyncer
import uk.co.fnurble.nfas.domain.AccountRules
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.AnimalResult
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
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
                RoundSyncer.Outcome.RETRY_LATER -> report.message ?: "Couldn't reach the NFAS server. It'll try again."
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
) : SyncingViewModel(syncer, accountStore, accounts) {
    val summary: StateFlow<HomeSummary?> = roundRepo.rounds.map { list ->
        val finished = list.filter { it.status == RoundStatus.COMPLETED }
        HomeSummary(
            inProgress = list.count { it.status == RoundStatus.IN_PROGRESS },
            finished = finished.size,
            best = finished.maxOfOrNull { it.totalPoints },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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
            onCreated(id)
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
