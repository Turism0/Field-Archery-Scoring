package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.fnurble.nfas.domain.Round
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.domain.TargetScore
import uk.co.fnurble.nfas.domain.TargetType
import uk.co.fnurble.nfas.domain.describe

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
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scorecard", style = MaterialTheme.typography.titleLarge)
                    Text(round.classLine, style = MaterialTheme.typography.bodyMedium)
                }
                Text("${round.totalPoints}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            val s = round.stats
            Text(
                "Inner kills ${s.innerKills} · Kills ${s.kills} · Wounds ${s.wounds} · Misses ${s.misses}" +
                    if (s.overrides > 0) " · Overrides ${s.overrides}" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            HorizontalDivider()
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.weight(1f, fill = false)) {
            items(round.targets, key = { it.targetNo }) { t ->
                ScorecardRow(t, isCurrent = t.targetNo == currentTarget, onGoTo = { onGoTo(t.targetNo) })
            }
            item {
                if (round.status == RoundStatus.IN_PROGRESS) {
                    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Finish round") }
                }
            }
        }
    }
}

@Composable
private fun ScorecardRow(t: TargetScore, isCurrent: Boolean, onGoTo: () -> Unit) {
    var expanded by rememberSaveable(t.targetNo) { mutableStateOf(false) }
    val multi = t.animals.size > 1
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { if (multi) expanded = !expanded else onGoTo() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${t.targetNo}",
                Modifier.width(40.dp),
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            Text(
                buildString {
                    if (t.type != TargetType.STANDARD) append(t.type.shortName)
                    if (t.type == TargetType.FAN) append(" ×${t.animals.size}")
                    if (t.isOverridden) append(if (isEmpty()) "Override" else " · Override")
                    if (!t.isComplete) append(if (isEmpty()) "Not scored" else " · not finished")
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (t.isOverridden) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
            )
            Text("${t.totalPoints}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (multi) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Hide breakdown" else "Show breakdown",
                    )
                }
            }
        }
        if (multi && expanded) {
            Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                t.animals.forEachIndexed { i, a ->
                    Row {
                        Text("${t.animalLabel(i)}: ${a.describe()}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${a.points}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextButton(onClick = onGoTo) { Text("Go to target ${t.targetNo}") }
            }
        }
    }
}
