package uk.co.fnurble.fieldarchery.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.fieldarchery.domain.AnimalResult
import uk.co.fnurble.fieldarchery.domain.BigGameScoring
import uk.co.fnurble.fieldarchery.domain.Hit
import uk.co.fnurble.fieldarchery.domain.RoundStatus
import uk.co.fnurble.fieldarchery.domain.TargetScore
import uk.co.fnurble.fieldarchery.domain.TargetType
import uk.co.fnurble.fieldarchery.domain.WoodsmanScoring
import uk.co.fnurble.fieldarchery.domain.WoodsmanArrow
import uk.co.fnurble.fieldarchery.domain.ordinal
import uk.co.fnurble.fieldarchery.ui.ScoringViewModel
import uk.co.fnurble.fieldarchery.data.GroupMember
import uk.co.fnurble.fieldarchery.data.local.GroupRole
import uk.co.fnurble.fieldarchery.domain.Round
import uk.co.fnurble.fieldarchery.domain.RoundLocation
import uk.co.fnurble.fieldarchery.ui.components.GroupStrip
import uk.co.fnurble.fieldarchery.ui.components.LocationChip
import uk.co.fnurble.fieldarchery.ui.components.LocationSheet
import uk.co.fnurble.fieldarchery.ui.components.NumberField
import uk.co.fnurble.fieldarchery.ui.RoundLocationViewModel
import uk.co.fnurble.fieldarchery.domain.LocationStatus
import uk.co.fnurble.fieldarchery.ui.components.RunningStats
import uk.co.fnurble.fieldarchery.ui.components.UkDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoringScreen(
    vm: ScoringViewModel,
    locationVm: RoundLocationViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onPickLocation: () -> Unit,
    /** Round details for the round being scored (in a group, the current archer's). */
    onEditDetails: (roundId: String) -> Unit,
) {
    val round by vm.round.collectAsStateWithLifecycle()
    val targetNo by vm.targetNo.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val currentId by vm.roundId.collectAsStateWithLifecycle()
    val inGroup = group.size > 1
    val member = group.firstOrNull { it.roundId == currentId }
    var showCard by rememberSaveable { mutableStateOf(false) }
    var showOverride by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showLocation by rememberSaveable { mutableStateOf(false) }
    var askedForName by rememberSaveable { mutableStateOf(false) }
    val signedIn by locationVm.signedIn.collectAsStateWithLifecycle()

    val r = round
    // Also when moving to another archer in a group.
    LaunchedEffect(r?.id) { if (r != null) vm.ensureTargetSelected(r) }
    // Round details changed (e.g. fewer targets): make sure the open target still exists.
    LaunchedEffect(r?.targetCount) { if (r != null) vm.ensureTargetSelected(r) }
    // Nothing known nearby: ask for the location's name once (they can also tap the location line later).
    LaunchedEffect(r?.location?.status) {
        if (r?.location?.status == LocationStatus.NEEDS_NAME && !askedForName) {
            askedForName = true
            showLocation = true
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                when {
                                    r == null || targetNo == 0 -> "Round"
                                    inGroup && member != null -> "Target $targetNo · ${member.name}"
                                    else -> "Target $targetNo of ${r.targetCount}"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (r != null) {
                                Text(
                                    (if (inGroup) "of ${r.targetCount} · " else "") + "${r.typeAndClass} · ${r.shootDate.format(UkDate)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(onClick = { showCard = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Scorecard")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                val what = if (inGroup) "group round" else "round"
                                if (r?.status == RoundStatus.COMPLETED) {
                                    DropdownMenuItem(text = { Text("Reopen $what") }, onClick = { menuOpen = false; vm.reopen() })
                                } else {
                                    DropdownMenuItem(text = { Text("Finish $what") }, onClick = { menuOpen = false; confirmFinish = true })
                                }
                                DropdownMenuItem(
                                    text = { Text(if (inGroup && member != null) "Round details – ${member.name}" else "Round details") },
                                    onClick = { menuOpen = false; onEditDetails(currentId) },
                                )
                                DropdownMenuItem(text = { Text("Delete $what") }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    },
                )
                if (r != null) {
                    LinearProgressIndicator(
                        progress = { if (r.targetCount == 0) 0f else r.targetsComplete.toFloat() / r.targetCount },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // The group shares one location: show the scorer's (theirs has the GPS position).
                    LocationChip(shownLocation(group, r), signedIn, onClick = { showLocation = true })
                    if (inGroup && targetNo > 0) {
                        GroupStrip(group, currentId, targetNo) { vm.selectMember(it) }
                    }
                }
            }
        },
        bottomBar = {
            if (r != null && targetNo > 0) {
                val target = r.target(targetNo)
                val next = if (inGroup) vm.nextInGroup() else null
                val prev = if (inGroup) vm.prevInGroup() else null
                TargetNavBar(
                    targetNo = targetNo,
                    targetCount = r.targetCount,
                    scored = target.isComplete,
                    // Group: the last archer on the last target finishes; otherwise on to the next archer.
                    last = if (inGroup) next == null || next.second > r.targetCount else targetNo >= r.targetCount,
                    prevEnabled = if (inGroup) prev != null && prev.second >= 1 else targetNo > 1,
                    nextLabel = next?.let { (m, n) -> if (n == targetNo) "Next: ${m.name}" else "Target $n: ${m.name}" },
                    onPrev = { vm.prev() },
                    onNext = { vm.next() },
                    onFinish = { confirmFinish = true },
                    onDone = onBack,
                    finished = r.status == RoundStatus.COMPLETED,
                )
            }
        },
    ) { padding ->
        if (r == null || targetNo == 0) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val target = r.target(targetNo)
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RunningStats(r.stats, compact = true)
            if (target.isWoodsman) WoodsmanEditor(
                target = target,
                onArrow = { vm.addArrow(it) },
                onUndo = { vm.undoArrow() },
                onOverride = { showOverride = true },
                onRemoveOverride = { vm.setOverride(null) },
                onClear = { vm.clearTarget() },
            ) else TargetEditor(
                target = target,
                onType = { type, fan -> vm.setType(type, fan) },
                onResult = { i, res -> vm.toggleResult(i, res) },
                onOverride = { showOverride = true },
                onRemoveOverride = { vm.setOverride(null) },
                onClear = { vm.clearTarget() },
            )
        }

        if (showOverride) {
            OverrideDialog(
                initial = target.overridePoints ?: target.calculatedPoints,
                onDismiss = { showOverride = false },
                onSave = { vm.setOverride(it); showOverride = false },
            )
        }
    }

    if (showLocation && r != null) {
        LocationSheet(locationVm, shownLocation(group, r), onDismiss = { showLocation = false }, onPickOnMap = onPickLocation)
    }
    if (showCard && r != null) {
        ScorecardSheet(
            round = r,
            currentTarget = targetNo,
            onDismiss = { showCard = false },
            onGoTo = { vm.goTo(it); showCard = false },
            onFinish = { showCard = false; confirmFinish = true },
        )
    }

    if (confirmFinish && r != null) {
        val unscored = r.targetCount - r.targetsComplete
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(if (inGroup) "Finish group round?" else "Finish round?") },
            text = {
                if (inGroup) {
                    val left = group.sumOf { it.round.targetCount - it.round.targetsComplete }
                    val friends = group.count { it.role == GroupRole.FRIEND }
                    Text(
                        (if (left > 0) "$left score(s) still to enter across the group. " else "") +
                            "Everyone's round is finished" +
                            (if (friends > 0) "; friends' rounds are sent to them to accept" else "") +
                            ". You can reopen it later if needed.",
                    )
                } else {
                    Text(
                        if (unscored > 0) "$unscored target(s) aren't scored yet. Total ${r.totalPoints}."
                        else "Total ${r.totalPoints}. You can reopen it later if needed.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finish(onFinished) }) { Text("Finish") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (inGroup) "Delete group round?" else "Delete round?") },
            text = {
                Text(
                    if (inGroup) "This removes everyone's round in the group and all their scores. Requests already sent to friends are taken back."
                    else "This removes the round and all its scores.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Prev / Next. Next stays disabled until the current target has a score. */
/**
 * Woodsman: one animal, record each arrow in order until a kill or 3 arrows. Shows the arrows so far,
 * the score, and buttons for the next arrow with what it would score.
 */
@Composable
private fun WoodsmanEditor(
    target: TargetScore,
    onArrow: (WoodsmanArrow) -> Unit,
    onUndo: () -> Unit,
    onOverride: () -> Unit,
    onRemoveOverride: () -> Unit,
    onClear: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Target ${target.targetNo}", style = MaterialTheme.typography.titleLarge)
                Text("Woodsman · shoot until you kill it", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Text("${target.totalPoints} / ${WoodsmanScoring.MAX_PER_TARGET}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Target options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Override score") }, onClick = { menuOpen = false; onOverride() })
                    DropdownMenuItem(text = { Text("Clear target") }, onClick = { menuOpen = false; onClear() })
                }
            }
        }

        if (target.isOverridden) {
            Card(colors = CardDefaults.cardColors(containerColor = scheme.tertiaryContainer)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Overridden to ${target.overridePoints} (calculated ${target.calculatedPoints})", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOverride) { Text("Edit") }
                    TextButton(onClick = onRemoveOverride) { Text("Remove") }
                }
            }
            return@Column
        }

        // The three arrows so far.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..WoodsmanScoring.MAX_ARROWS).forEach { n ->
                val arrow = target.arrows.getOrNull(n - 1)
                val unused = arrow == null && WoodsmanScoring.isFinished(target.arrows)
                Surface(
                    modifier = Modifier.weight(1f).height(64.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = when (arrow) {
                        WoodsmanArrow.KILL -> scheme.primary
                        WoodsmanArrow.WOUND -> scheme.secondaryContainer
                        WoodsmanArrow.MISS -> scheme.errorContainer
                        null -> scheme.surfaceContainerHigh
                    },
                    contentColor = when (arrow) {
                        WoodsmanArrow.KILL -> scheme.onPrimary
                        WoodsmanArrow.WOUND -> scheme.onSecondaryContainer
                        WoodsmanArrow.MISS -> scheme.onErrorContainer
                        null -> scheme.onSurfaceVariant
                    },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("${ordinal(n)} arrow", style = MaterialTheme.typography.labelSmall)
                        Text(
                            when {
                                arrow != null -> arrow.displayName
                                unused -> "–"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        val finished = WoodsmanScoring.isFinished(target.arrows)
        if (!finished) {
            val next = target.arrows.size + 1
            Text("${ordinal(next)} arrow", style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScoreTile(WoodsmanScoring.killPoints(next), "Kill", selected = false, modifier = Modifier.weight(1f)) { onArrow(WoodsmanArrow.KILL) }
                ScoreTile(WoodsmanScoring.WOUND, "Wound", selected = false, modifier = Modifier.weight(1f)) { onArrow(WoodsmanArrow.WOUND) }
            }
            MissTile(selected = false) { onArrow(WoodsmanArrow.MISS) }
        } else {
            Text(
                if (target.arrows.last() == WoodsmanArrow.KILL) "Killed with the ${ordinal(target.arrows.size)} arrow."
                else "All three arrows shot.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        if (target.arrows.isNotEmpty()) {
            TextButton(onClick = onUndo) { Text("Undo last arrow") }
        }
    }
}

@Composable
private fun TargetNavBar(
    targetNo: Int,
    targetCount: Int,
    scored: Boolean,
    finished: Boolean,
    last: Boolean = targetNo >= targetCount,
    prevEnabled: Boolean = targetNo > 1,
    /** Group: "Next: Dave" instead of "Next target". */
    nextLabel: String? = null,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onPrev,
                    enabled = prevEnabled,
                    modifier = Modifier.height(52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                    Text("Prev")
                }
                Button(
                    onClick = when {
                        !last -> onNext
                        finished -> onDone
                        else -> onFinish
                    },
                    enabled = scored,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(
                        when {
                            !scored -> "Score target $targetNo to continue"
                            last -> if (finished) "Done" else "Finish round"
                            else -> nextLabel ?: "Next target"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (scored && !last) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun TargetEditor(
    target: TargetScore,
    onType: (TargetType, Int) -> Unit,
    onResult: (Int, AnimalResult) -> Unit,
    onOverride: () -> Unit,
    onRemoveOverride: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TargetHeader(target, onType, onOverride, onClear)

        if (target.type == TargetType.FAN) FanCountStepper(target.animals.size) { onType(TargetType.FAN, it) }

        if (target.isOverridden) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Overridden to ${target.overridePoints} (calculated ${target.calculatedPoints})",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOverride) { Text("Edit") }
                    TextButton(onClick = onRemoveOverride) { Text("Remove") }
                }
            }
        } else {
            val multi = target.animals.size > 1
            target.animals.forEachIndexed { i, result ->
                val waiting = target.type == TargetType.PREDATOR_PREY && i == 1 && target.animals[0] == AnimalResult.Pending
                AnimalScorer(
                    label = if (multi) target.animalLabel(i) else null,
                    result = result,
                    available = target.arrowsAvailable(i),
                    waitingOnPredator = waiting,
                    onResult = { onResult(i, it) },
                )
            }
        }
    }
}

/**
 * "Target 5 · 20 / 24" plus a small type chip and an overflow menu. Changing the target type,
 * override and clear are tucked away here as they're rarely needed.
 */
@Composable
private fun TargetHeader(
    target: TargetScore,
    onType: (TargetType, Int) -> Unit,
    onOverride: () -> Unit,
    onClear: () -> Unit,
) {
    var typeMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${target.totalPoints}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    " / ${target.maxPoints}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                if (target.isComplete) "Target scored" else "Not scored yet",
                style = MaterialTheme.typography.labelMedium,
                color = if (target.isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            AssistChip(
                onClick = { typeMenu = true },
                label = { Text(target.type.displayName) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change target type", Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                // Big Game target types only (Woodsman targets belong to Woodsman rounds).
                TargetType.entries.filter { it != TargetType.WOODSMAN }.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.displayName) },
                        leadingIcon = { if (t == target.type) Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = {
                            typeMenu = false
                            if (t != target.type) onType(t, TargetScore.MIN_FAN)
                        },
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Target options") }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (target.isOverridden) "Edit override" else "Override score") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { moreMenu = false; onOverride() },
                )
                DropdownMenuItem(
                    text = { Text("Clear target") },
                    leadingIcon = { Icon(Icons.Filled.Clear, contentDescription = null) },
                    onClick = { moreMenu = false; onClear() },
                )
            }
        }
    }
}

@Composable
private fun FanCountStepper(count: Int, onCount: (Int) -> Unit) {
    OutlinedCard {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Animals in fan", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            FilledTonalIconButton(onClick = { onCount(count - 1) }, enabled = count > TargetScore.MIN_FAN) { Text("–") }
            Text("$count", Modifier.width(40.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
            FilledTonalIconButton(onClick = { onCount(count + 1) }, enabled = count < TargetScore.MAX_FAN) { Text("+") }
        }
    }
}

/**
 * Score tiles for one animal, laid out like a Big Game card:
 *
 *            Inner kill  Kill  Wound
 *   1st         24        20     16
 *   2nd                   14     10
 *   3rd                    8      4
 *   [            Miss            ]
 *
 * Only the arrows still available are shown (e.g. the prey after the predator took 2 arrows).
 */
@Composable
private fun AnimalScorer(
    label: String?,
    result: AnimalResult,
    available: Int,
    waitingOnPredator: Boolean,
    onResult: (AnimalResult) -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                waitingOnPredator -> Text(
                    "Score the predator first",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                available == 0 -> {
                    Text(
                        "No arrows left for this animal",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    for (arrow in 1..available) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                ordinal(arrow),
                                Modifier.width(32.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Columns: inner kill, kill, wound. 2nd/3rd arrows have no inner kill.
                            listOf(Hit.INNER_KILL, Hit.KILL, Hit.WOUND).forEach { hit ->
                                if (BigGameScoring.isValid(arrow, hit)) {
                                    val option = AnimalResult.Scored(arrow, hit)
                                    ScoreTile(
                                        points = BigGameScoring.points(arrow, hit),
                                        label = hit.displayName,
                                        selected = result == option,
                                        modifier = Modifier.weight(1f),
                                    ) { onResult(option) }
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    MissTile(selected = result == AnimalResult.Missed) { onResult(AnimalResult.Missed) }
                }
            }
        }
    }

    if (label == null) {
        body()
    } else {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${result.points}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider()
                body()
            }
        }
    }
}

@Composable
private fun ScoreTile(points: Int, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.primary else scheme.surfaceContainerHigh,
        contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("$points", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MissTile(selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.error else scheme.surface,
        contentColor = if (selected) scheme.onError else scheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("Miss · 0", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun OverrideDialog(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        title = { Text("Override score") },
        text = {
            Column {
                Text("For non-standard scoring. The scorecard will show this target as overridden.")
                Spacer(Modifier.height(12.dp))
                NumberField(label = "Score", value = text, onValue = { text = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { text.toIntOrNull()?.let(onSave) }, enabled = text.toIntOrNull() != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** In a group everyone is at the same location; the scorer's round has it (and the GPS position). */
private fun shownLocation(group: List<GroupMember>, r: Round): RoundLocation =
    group.firstOrNull { it.role == GroupRole.SCORER }?.round?.location ?: r.location
