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
import uk.co.fnurble.nfas.data.Profile
import uk.co.fnurble.nfas.data.ProfileRepository
import uk.co.fnurble.nfas.data.RoundRepository
import uk.co.fnurble.nfas.data.RoundSummary
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.AnimalResult
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.domain.Round
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.domain.TargetScore
import uk.co.fnurble.nfas.domain.TargetType
import uk.co.fnurble.nfas.domain.resolveAgeGroup
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

class HomeViewModel(profiles: ProfileRepository, roundRepo: RoundRepository) : ViewModel() {
    val rounds: StateFlow<List<RoundSummary>?> =
        roundRepo.rounds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True once class and main bow style are set, so a round can be started. */
    val profileReady: StateFlow<Boolean> = profiles.profile.map { p ->
        p.archerClass != null && p.mainBowStyle != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
}

// ---------------- Profile ----------------

class ProfileViewModel(private val repo: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow<Profile?>(null)
    val state: StateFlow<Profile?> = _state.asStateFlow()

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
