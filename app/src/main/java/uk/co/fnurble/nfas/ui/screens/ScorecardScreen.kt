package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.domain.Round
import uk.co.fnurble.nfas.domain.RoundStats
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.ui.ScorecardViewModel
import uk.co.fnurble.nfas.ui.components.RunningStats
import uk.co.fnurble.nfas.ui.components.UkDateLong
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full scorecard for a round: shown when a round is finished and when opening a past round.
 * "Edit scores" (or tapping a target) opens the scoring screen to make changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScorecardScreen(
    vm: ScorecardViewModel,
    onBack: () -> Unit,
    onEdit: (targetNo: Int) -> Unit,
) {
    val round by vm.round.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scorecard") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { onEdit(0) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit scores") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (round?.status == RoundStatus.COMPLETED) {
                                DropdownMenuItem(
                                    text = { Text("Reopen round") },
                                    onClick = { menuOpen = false; vm.reopen() },
                                )
                            }
                            DropdownMenuItem(text = { Text("Delete round") }, onClick = { menuOpen = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(0) },
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                text = { Text("Edit scores") },
            )
        },
    ) { padding ->
        val r = round
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundHeader(r)
                    RunningStats(r.stats)
                    MoreStats(r.stats)
                    Text(
                        "Targets",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(r.targets, key = { it.targetNo }) { t ->
                ScorecardTargetRow(
                    t = t,
                    isCurrent = false,
                    locked = !r.canGoTo(t.targetNo),
                    onGoTo = { onEdit(t.targetNo) },
                    goToLabel = "Edit target ${t.targetNo}",
                )
                HorizontalDivider(Modifier.padding(start = 72.dp))
            }
        }
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
internal fun RoundHeader(r: Round) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (r.status == RoundStatus.COMPLETED) scheme.primary else scheme.tertiaryContainer,
                contentColor = if (r.status == RoundStatus.COMPLETED) scheme.onPrimary else scheme.onTertiaryContainer,
            ) {
                Text(
                    if (r.status == RoundStatus.COMPLETED) "FINISHED" else "IN PROGRESS",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(r.shootDate.format(UkDateLong), style = MaterialTheme.typography.titleLarge)
            Text(
                "${r.classLine} · ${r.targetCount} targets",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${r.totalPoints}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text("points", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
}

/** Percentages for the round. Rates leave out overridden targets (no arrows recorded). */
@Composable
internal fun MoreStats(s: RoundStats) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                PercentStat("Of maximum", s.shareOfMax)
                PercentStat("1st arrow", s.firstArrowRate)
                PercentStat("Kills", s.killRate)
                PercentStat("Misses", s.missRate)
            }
            Text(
                "${s.animalsScored} animals scored · maximum ${s.animalsScored * 24} points",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun RowScope.PercentStat(label: String, value: Double?) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value?.let { String.format(Locale.UK, "%d%%", (it * 100).roundToInt()) } ?: "–",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
