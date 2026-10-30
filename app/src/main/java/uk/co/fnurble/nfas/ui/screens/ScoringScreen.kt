package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.domain.AnimalResult
import uk.co.fnurble.nfas.domain.BigGameScoring
import uk.co.fnurble.nfas.domain.Round
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.domain.TargetScore
import uk.co.fnurble.nfas.domain.TargetType
import uk.co.fnurble.nfas.domain.ordinal
import uk.co.fnurble.nfas.ui.ScoringViewModel
import uk.co.fnurble.nfas.ui.components.NumberField
import uk.co.fnurble.nfas.ui.components.UkDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoringScreen(vm: ScoringViewModel, onBack: () -> Unit) {
    val round by vm.round.collectAsStateWithLifecycle()
    val targetNo by vm.targetNo.collectAsStateWithLifecycle()
    var showCard by rememberSaveable { mutableStateOf(false) }
    var showOverride by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val r = round
    LaunchedEffect(r != null) { if (r != null) vm.ensureTargetSelected(r) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (r != null && targetNo > 0) "Target $targetNo of ${r.targetCount}" else "Round")
                        if (r != null) {
                            Text(
                                "${r.classLine} · ${r.shootDate.format(UkDate)} · Total ${r.totalPoints}",
                                style = MaterialTheme.typography.bodySmall,
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
        },
        bottomBar = {
            if (r != null && targetNo > 0) {
                BottomAppBar {
                    OutlinedButton(onClick = { vm.goTo(targetNo - 1) }, enabled = targetNo > 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                        Text("Prev")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${r.targetsComplete}/${r.targetCount} scored",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    if (targetNo < r.targetCount) {
                        Button(onClick = { vm.goTo(targetNo + 1) }) {
                            Text("Next")
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    } else {
                        Button(onClick = { showCard = true }) { Text("Scorecard") }
                    }
                }
            }
        },
    ) { padding ->
        if (r == null || targetNo == 0) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val target = r.target(targetNo)
        TargetEditor(
            target = target,
            modifier = Modifier.padding(padding),
            onType = { type, fan -> vm.setType(type, fan) },
            onResult = { i, res -> vm.toggleResult(i, res) },
            onOverride = { showOverride = true },
            onRemoveOverride = { vm.setOverride(null) },
            onClear = { vm.clearTarget() },
        )

        if (showOverride) {
            OverrideDialog(
                initial = target.overridePoints ?: target.calculatedPoints,
                onDismiss = { showOverride = false },
                onSave = { vm.setOverride(it); showOverride = false },
            )
        }
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
                    if (unscored > 0) "$unscored target(s) aren't fully scored yet. Total ${r.totalPoints}."
                    else "Total ${r.totalPoints}. You can reopen it later if needed.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finish(onBack) }) { Text("Finish") } },
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

@Composable
private fun TargetEditor(
    target: TargetScore,
    modifier: Modifier,
    onType: (TargetType, Int) -> Unit,
    onResult: (Int, AnimalResult) -> Unit,
    onOverride: () -> Unit,
    onRemoveOverride: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val types = TargetType.entries
            types.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = target.type == t,
                    onClick = { if (target.type != t) onType(t, TargetScore.MIN_FAN) },
                    shape = SegmentedButtonDefaults.itemShape(i, types.size),
                ) { Text(if (t == TargetType.PREDATOR_PREY) "Pred / Prey" else t.displayName) }
            }
        }

        if (target.type == TargetType.FAN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Animals in fan", Modifier.weight(1f))
                OutlinedButton(
                    onClick = { onType(TargetType.FAN, target.animals.size - 1) },
                    enabled = target.animals.size > TargetScore.MIN_FAN,
                ) { Text("–") }
                Text("${target.animals.size}", Modifier.width(40.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { onType(TargetType.FAN, target.animals.size + 1) },
                    enabled = target.animals.size < TargetScore.MAX_FAN,
                ) { Text("+") }
            }
        }

        if (target.isOverridden) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Score overridden: ${target.overridePoints} (calculated ${target.calculatedPoints})",
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = onOverride) { Text("Edit") }
                    TextButton(onClick = onRemoveOverride) { Text("Remove") }
                }
            }
        }

        val dim = if (target.isOverridden) Modifier.alpha(0.5f) else Modifier
        target.animals.forEachIndexed { i, result ->
            AnimalCard(
                label = target.animalLabel(i),
                result = result,
                available = target.arrowsAvailable(i),
                waitingOnPredator = target.type == TargetType.PREDATOR_PREY && i == 1 && target.animals[0] == AnimalResult.Pending,
                modifier = dim,
                onResult = { onResult(i, it) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Target score",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${target.totalPoints}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(" / ${target.maxPoints}", style = MaterialTheme.typography.titleMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!target.isOverridden) OutlinedButton(onClick = onOverride) { Text("Override score") }
            TextButton(onClick = onClear) { Text("Clear target") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AnimalCard(
    label: String,
    result: AnimalResult,
    available: Int,
    waitingOnPredator: Boolean,
    modifier: Modifier,
    onResult: (AnimalResult) -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    when {
                        waitingOnPredator -> "Score the predator first"
                        available == 0 -> "No arrows left"
                        available < BigGameScoring.MAX_ARROWS -> "$available arrow(s) left"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(8.dp))
                Text("${result.points}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (available > 0) {
                for (arrow in 1..BigGameScoring.MAX_ARROWS) {
                    val enabled = arrow <= available
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ordinal(arrow), Modifier.width(36.dp), style = MaterialTheme.typography.labelLarge)
                        BigGameScoring.allowedHits(arrow).forEach { hit ->
                            val option = AnimalResult.Scored(arrow, hit)
                            ScoreButton(
                                text = "${hit.displayName}\n${BigGameScoring.points(arrow, hit)}",
                                selected = result == option,
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                            ) { onResult(option) }
                        }
                        // keep columns lined up on 2nd/3rd arrow rows (no inner kill)
                        if (BigGameScoring.allowedHits(arrow).size < 3) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (!waitingOnPredator) {
                ScoreButton(
                    text = if (available == 0) "No score" else "Miss (0)",
                    selected = result == AnimalResult.Missed,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                ) { onResult(AnimalResult.Missed) }
            }
        }
    }
}

@Composable
private fun ScoreButton(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = pad) {
            Text(text, textAlign = TextAlign.Center)
        }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = pad) {
            Text(text, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun OverrideDialog(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Override score") },
        text = {
            Column {
                Text("For non-standard scoring. The card will show this target as overridden.")
                Spacer(Modifier.height(8.dp))
                NumberField(label = "Score", value = text, onValue = { text = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { text.toIntOrNull()?.let(onSave) }, enabled = text.toIntOrNull() != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
