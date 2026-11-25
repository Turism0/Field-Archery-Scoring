package uk.co.fieldarcheryscoring.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.fieldarcheryscoring.data.EquipmentRepository
import uk.co.fieldarcheryscoring.data.ProfileRepository
import uk.co.fieldarcheryscoring.data.RoundRepository
import uk.co.fieldarcheryscoring.domain.ArrowSet
import uk.co.fieldarcheryscoring.domain.Bow
import uk.co.fieldarcheryscoring.domain.Equipment

/** Equipment on Home (3.2.24, Pete): your bows and arrows, with how you've shot with each. */
class EquipmentViewModel(private val equipment: EquipmentRepository, rounds: RoundRepository) : ViewModel() {
    /** Bows and arrows not deleted, by name. */
    val bows: StateFlow<List<Bow>?> = equipment.bows.map { l -> l.filter { !it.deleted }.sortedBy { Equipment.label(it).lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val arrows: StateFlow<List<ArrowSet>?> = equipment.arrows.map { l -> l.filter { !it.deleted }.sortedBy { Equipment.label(it).lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every bow (deleted ones too), to name the bows arrows are used with. */
    val allBows: StateFlow<List<Bow>> = equipment.bows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Adding or changing a bow. Nav argument "bowId" ("new" for a new one). */
class BowEditViewModel(
    private val equipment: EquipmentRepository,
    saved: SavedStateHandle,
    profiles: ProfileRepository? = null,
) : ViewModel() {
    private val given: String = checkNotNull(saved["bowId"])
    val isNew: Boolean = given == "new"
    private val id: String = if (isNew) equipment.newId() else given

    private val _form = MutableStateFlow<Equipment.BowForm?>(null)
    val form: StateFlow<Equipment.BowForm?> = _form.asStateFlow()

    private val _tried = MutableStateFlow(false)
    /** Save was tapped: show what's missing. */
    val tried: StateFlow<Boolean> = _tried.asStateFlow()

    init {
        viewModelScope.launch {
            _form.value = if (isNew) {
                // A new bow starts in your main bow style.
                Equipment.BowForm(bowStyles = setOfNotNull(profiles?.get()?.mainBowStyle))
            } else {
                equipment.bows.first().firstOrNull { it.id == id }?.let { Equipment.BowForm.of(it) } ?: Equipment.BowForm()
            }
        }
    }

    fun update(change: (Equipment.BowForm) -> Equipment.BowForm) = _form.update { it?.let(change) }

    fun save(onDone: () -> Unit) {
        val f = _form.value ?: return
        _tried.value = true
        val bow = f.toBow(id, System.currentTimeMillis()) ?: return
        viewModelScope.launch { equipment.saveBow(bow); onDone() }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { equipment.deleteBow(id); onDone() }
    }
}

/** Adding or changing a set of arrows. Nav argument "arrowId" ("new" for a new one). */
class ArrowsEditViewModel(private val equipment: EquipmentRepository, saved: SavedStateHandle) : ViewModel() {
    private val given: String = checkNotNull(saved["arrowId"])
    val isNew: Boolean = given == "new"
    private val id: String = if (isNew) equipment.newId() else given

    private val _form = MutableStateFlow<Equipment.ArrowForm?>(null)
    val form: StateFlow<Equipment.ArrowForm?> = _form.asStateFlow()

    private val _tried = MutableStateFlow(false)
    val tried: StateFlow<Boolean> = _tried.asStateFlow()

    /** Your bows to tick (not deleted, plus any deleted one these arrows still name). */
    val bows: StateFlow<List<Bow>> = combine(equipment.bows, _form) { list, f ->
        list.filter { !it.deleted || (f != null && it.id in f.bowIds) }.sortedBy { Equipment.label(it).lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _form.value = if (isNew) {
                // With only one bow, new arrows are for it.
                val live = equipment.bows.first().filter { !it.deleted }
                Equipment.ArrowForm(bowIds = if (live.size == 1) setOf(live.first().id) else emptySet())
            } else {
                equipment.arrows.first().firstOrNull { it.id == id }?.let { Equipment.ArrowForm.of(it) } ?: Equipment.ArrowForm()
            }
        }
    }

    fun update(change: (Equipment.ArrowForm) -> Equipment.ArrowForm) = _form.update { it?.let(change) }

    /** Tidies the spine when leaving the field ("0.500" → "500", "45/50#" → "45-50"). */
    fun tidySpine() = update { f -> Equipment.spine(f.spine)?.let { f.copy(spine = it.text) } ?: f }

    fun save(onDone: () -> Unit) {
        val f = _form.value ?: return
        _tried.value = true
        val arrows = f.toArrows(id, System.currentTimeMillis()) ?: return
        viewModelScope.launch { equipment.saveArrows(arrows); onDone() }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { equipment.deleteArrows(id); onDone() }
    }
}
