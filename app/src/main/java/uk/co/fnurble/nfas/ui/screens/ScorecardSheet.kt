package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.fnurble.nfas.domain.Round
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.domain.TargetScore
import uk.co.fnurble.nfas.domain.TargetType
import uk.co.fnurble.nfas.domain.describe
import uk.co.fnurble.nfas.ui.components.RunningStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScorecardSheet(
    round: Round,
    currentTarget: Int,
    onDismiss: () -> Unit,
    onGoTo: (Int) -> Unit,
    onFinish: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("Scorecard", style = MaterialTheme.typography.headlineSmall)
                Text(round.typeAndClass, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RunningStats(round.stats)
        }
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), modifier = Modifier.weight(1f, fill = false)) {
            items(round.targets, key = { it.targetNo }) { t ->
                ScorecardTargetRow(
                    t = t,
                    isCurrent = t.targetNo == currentTarget,
                    locked = !round.canGoTo(t.targetNo),
                    onGoTo = { onGoTo(t.targetNo) },
                )
                HorizontalDivider(Modifier.padding(start = 72.dp))
            }
            item {
                if (round.status == RoundStatus.IN_PROGRESS) {
                    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Finish round") }
                }
            }
        }
    }
}

/**
 * One target on a scorecard. Tapping a standard target opens it; predator/prey and fans expand
 * to show each animal first, with a button to open the target.
 */
@Composable
internal fun ScorecardTargetRow(
    t: TargetScore,
    isCurrent: Boolean,
    locked: Boolean,
    onGoTo: () -> Unit,
    goToLabel: String = "Go to target ${t.targetNo}",
    /** Friends' scorecards: predator/prey and fans still expand, but nothing opens for editing. */
    readOnly: Boolean = false,
) {
    var expanded by rememberSaveable(t.targetNo) { mutableStateOf(false) }
    val multi = t.animals.size > 1
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.alpha(if (locked) 0.45f else 1f)) {
        ListItem(
            modifier = Modifier.clickable(enabled = !locked && (multi || !readOnly)) {
                if (multi) expanded = !expanded else onGoTo()
            },
            colors = ListItemDefaults.colors(
                containerColor = if (isCurrent) scheme.secondaryContainer else scheme.surfaceContainerLow,
            ),
            leadingContent = {
                Surface(
                    shape = CircleShape,
                    color = if (isCurrent) scheme.primary else scheme.surfaceContainerHighest,
                    contentColor = if (isCurrent) scheme.onPrimary else scheme.onSurface,
                    modifier = Modifier.size(40.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (locked) Icon(Icons.Filled.Lock, contentDescription = "Locked", Modifier.size(18.dp))
                        else Text("${t.targetNo}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            },
            headlineContent = {
                Text(
                    when {
                        locked -> "Target ${t.targetNo}"
                        t.isWoodsman && !t.isOverridden && t.arrows.isNotEmpty() -> t.arrows.describe()
                        !t.isComplete -> "Not scored"
                        t.isOverridden -> "Overridden"
                        t.type == TargetType.STANDARD -> t.animals.first().describe()
                        else -> t.type.displayName + if (t.type == TargetType.FAN) " ×${t.animals.size}" else ""
                    },
                )
            },
            supportingContent = if (t.isOverridden && t.isComplete) {
                { Text("Calculated ${t.calculatedPoints}", color = scheme.tertiary) }
            } else null,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (t.isComplete || t.totalPoints > 0) "${t.totalPoints}" else "–",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (multi && !locked) {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (expanded) "Hide breakdown" else "Show breakdown",
                            )
                        }
                    }
                }
            },
        )
        if (multi && expanded && !locked) {
            Column(
                Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                t.animals.forEachIndexed { i, a ->
                    Row {
                        Text("${t.animalLabel(i)}: ${a.describe()}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${a.points}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (!readOnly) TextButton(onClick = onGoTo) { Text(goToLabel) }
            }
        }
    }
}
