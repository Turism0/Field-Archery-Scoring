package uk.co.fnurble.nfas.ui.screens

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
import uk.co.fnurble.nfas.domain.AnimalResult
import uk.co.fnurble.nfas.domain.BigGameScoring
import uk.co.fnurble.nfas.domain.Hit
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.domain.TargetScore
import uk.co.fnurble.nfas.domain.TargetType
import uk.co.fnurble.nfas.domain.ordinal
import uk.co.fnurble.nfas.ui.ScoringViewModel
import uk.co.fnurble.nfas.ui.components.LocationChip
import uk.co.fnurble.nfas.ui.components.LocationSheet
import uk.co.fnurble.nfas.ui.components.NumberField
import uk.co.fnurble.nfas.ui.RoundLocationViewModel
import uk.co.fnurble.nfas.domain.LocationStatus
import uk.co.fnurble.nfas.ui.components.RunningStats
import uk.co.fnurble.nfas.ui.components.UkDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoringScreen(vm: ScoringViewModel, locationVm: RoundLocationViewModel, onBack: () -> Unit, onFinished: () -> Unit) {
    val round by vm.round.collectAsStateWithLifecycle()
    val targetNo by vm.targetNo.collectAsStateWithLifecycle()
    var showCard by rememberSaveable { mutableStateOf(false) }
    var showOverride by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showLocation by rememberSaveable { mutableStateOf(false) }
    var askedForName by rememberSaveable { mutableStateOf(false) }
    val signedIn by locationVm.signedIn.collectAsStateWithLifecycle()

    val r = round
    LaunchedEffect(r != null) { if (r != null) vm.ensureTargetSelected(r) }
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
                                if (r != null && targetNo > 0) "Target $targetNo of ${r.targetCount}" else "Round",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (r != null) {
                                Text(
                                    "${r.classLine} · ${r.shootDate.format(UkDate)}",
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
                                if (r?.status == RoundStatus.COMPLETED) {
                                    DropdownMenuItem(text = { Text("Reopen round") }, onClick = { menuOpen = false; vm.reopen() })
                                } else {
                                    DropdownMenuItem(text = { Text("Finish round") }, onClick = { menuOpen = false; confirmFinish = true })
                                }
                                DropdownMenuItem(text = { Text("Delete round") }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    },
                )
                if (r != null) {
                    LinearProgressIndicator(
                        progress = { if (r.targetCount == 0) 0f else r.targetsComplete.toFloat() / r.targetCount },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LocationChip(r.location, signedIn, onClick = { showLocation = true })
                }
            }
        },
        bottomBar = {
            if (r != null && targetNo > 0) {
                val target = r.target(targetNo)
                TargetNavBar(
                    targetNo = targetNo,
                    targetCount = r.targetCount,
                    scored = target.isComplete,
                    onPrev = { vm.goTo(targetNo - 1) },
                    onNext = { vm.goTo(targetNo + 1) },
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
            TargetEditor(
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
        LocationSheet(locationVm, r.location, onDismiss = { showLocation = false })
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
            title = { Text("Finish round?") },
            text = {
                Text(
                    if (unscored > 0) "$unscored target(s) aren't scored yet. Total ${r.totalPoints}."
                    else "Total ${r.totalPoints}. You can reopen it later if needed.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finish(onFinished) }) { Text("Finish") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete round?") },
            text = { Text("This removes the round and all its scores.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Prev / Next. Next stays disabled until the current target has a score. */
@Composable
private fun TargetNavBar(
    targetNo: Int,
    targetCount: Int,
    scored: Boolean,
    finished: Boolean,
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
                    enabled = targetNo > 1,
                    modifier = Modifier.height(52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                    Text("Prev")
                }
                val last = targetNo >= targetCount
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
                            else -> "Next target"
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
                TargetType.entries.forEach { t ->
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
