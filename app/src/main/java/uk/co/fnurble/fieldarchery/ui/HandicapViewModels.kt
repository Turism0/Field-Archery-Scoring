package uk.co.fnurble.fieldarchery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.fnurble.fieldarchery.data.AccountStore
import uk.co.fnurble.fieldarchery.data.HandicapEvent
import uk.co.fnurble.fieldarchery.data.HandicapOverview
import uk.co.fnurble.fieldarchery.data.HandicapRepository
import uk.co.fnurble.fieldarchery.data.ProfileRepository
import uk.co.fnurble.fieldarchery.data.RoundRepository
import uk.co.fnurble.fieldarchery.data.remote.ApiException
import uk.co.fnurble.fieldarchery.domain.AccountRole
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.domain.HandicapModel
import uk.co.fnurble.fieldarchery.domain.PersonalHandicap
import uk.co.fnurble.fieldarchery.domain.Round
import uk.co.fnurble.fieldarchery.domain.handicapEntry
import uk.co.fnurble.fieldarchery.domain.resolveAgeGroup
import java.time.LocalDate

// ---------------- Handicaps (Home tile; calculator 2.2.05, personal handicap and multipliers 2.9) ----------------

data class CalculatorForm(
    val scoreText: String = "",
    val targetsText: String = "40",
    val bowStyle: BowStyle? = null,
    val archerClass: ArcherClass? = null,
    val ageGroup: AgeGroup? = null,
) {
    val score: Int? get() = scoreText.toIntOrNull()
    val targets: Int? get() = targetsText.toIntOrNull()?.takeIf { it in 1..100 }
    /** Ready to work out: a score no more than 24 a target, and a bow and class. */
    val archer: HandicapModel.Archer? get() =
        if (bowStyle != null && archerClass != null && ageGroup != null) HandicapModel.Archer(bowStyle, archerClass, ageGroup) else null
    val scoreProblem: String? get() {
        val s = score ?: return null
        val t = targets ?: return "Targets must be 1 to 100."
        return if (s > 24 * t) "More than $t targets allow (${24 * t})." else null
    }
}

/**
 * Handicaps (2.9): your personal handicap for each bow style, the class multipliers, and the calculator (a score, its
 * targets, bow and class: the same performance for every bow style and class).
 */
class HandicapCalculatorViewModel(profiles: ProfileRepository, handicap: HandicapRepository, rounds: RoundRepository) : ViewModel() {
    val model: StateFlow<HandicapModel?> = handicap.model

    /** Your finished rounds (to open the ones a handicap counts). */
    val finished: StateFlow<List<Round>> = rounds.finishedRounds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Your personal handicaps, one per bow style, most rounds first. */
    val handicaps: StateFlow<List<PersonalHandicap.Handicap>> = rounds.finishedRounds
        .map { list -> PersonalHandicap.all(list.mapNotNull { it.handicapEntry() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Your bow style and class from the profile, highlighted in the multipliers (null until read). */
    private val _me = MutableStateFlow<HandicapModel.Archer?>(null)
    val me: StateFlow<HandicapModel.Archer?> = _me.asStateFlow()

    private val _form = MutableStateFlow(CalculatorForm())
    val form: StateFlow<CalculatorForm> = _form.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        // Start with your own bow and class.
        viewModelScope.launch {
            val p = profiles.get()
            val age = resolveAgeGroup(p.ageGroupManual, p.dateOfBirth, LocalDate.now())
            val bow = p.mainBowStyle
            val cls = p.archerClass
            if (bow != null && cls != null && age != null) _me.value = HandicapModel.Archer(bow, cls, age)
            _form.update {
                it.copy(
                    bowStyle = it.bowStyle ?: p.mainBowStyle,
                    archerClass = it.archerClass ?: p.archerClass,
                    ageGroup = it.ageGroup ?: resolveAgeGroup(p.ageGroupManual, p.dateOfBirth, LocalDate.now()),
                )
            }
        }
        viewModelScope.launch { handicap.refreshIfStale() }
    }

    fun update(change: (CalculatorForm) -> CalculatorForm) = _form.update(change)

    private val repo = handicap
    /** No model yet (never had signal since installing): try again. */
    fun download() = viewModelScope.launch {
        _refreshing.value = true
        repo.refresh()
        _refreshing.value = false
    }
}

// ---------------- Admin → Handicap ----------------

class AdminHandicapViewModel(private val repo: HandicapRepository, accountStore: AccountStore) : ViewModel() {
    val role: StateFlow<AccountRole> = accountStore.role

    private val _data = MutableStateFlow<Load<HandicapOverview>>(Load.Loading)
    val data: StateFlow<Load<HandicapOverview>> = _data.asStateFlow()

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
            _data.value = try {
                Load.Ready(repo.overview())
            } catch (e: ApiException) {
                Load.Failed(e.message ?: "Couldn't load the handicap.")
            }
            _refreshing.value = false
        }
    }

    /** 36 or 40 by hand, or null for the job's own count. */
    fun setTargets(e: HandicapEvent, targets: Int?) = act(
        if (targets == null) "${e.name}: back to automatic. The server job sets it on its next weekly run."
        else "${e.name}: $targets targets a day. The handicap is refitted on the next weekly run.",
    ) { repo.setTargets(e.champs, e.year, targets) }

    fun fetchNow() = act(null) { _message.value = repo.requestFetch() }

    fun activate(modelId: Int) = act("Model $modelId is now the handicap everyone uses.") { repo.activate(modelId) }

    private fun act(done: String?, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
                done?.let { _message.value = it }
                _data.value = Load.Ready(repo.overview())
            } catch (e: ApiException) {
                _message.value = e.message
            } finally {
                _busy.value = false
            }
        }
    }
}
