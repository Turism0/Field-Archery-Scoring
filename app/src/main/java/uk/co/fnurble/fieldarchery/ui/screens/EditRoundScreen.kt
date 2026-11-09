package uk.co.fnurble.fieldarchery.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.fieldarchery.data.RoundRepository
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.ui.EditRoundViewModel
import uk.co.fnurble.fieldarchery.ui.components.BottomActionBar
import uk.co.fnurble.fieldarchery.ui.components.DateField
import uk.co.fnurble.fieldarchery.ui.components.NumberField
import uk.co.fnurble.fieldarchery.ui.components.OptionDropdown
import uk.co.fnurble.fieldarchery.ui.components.SectionCard
import uk.co.fnurble.fieldarchery.ui.components.SegmentedChoice

/**
 * Edit a round's details (the choices made when it was created): date, number of targets, class, age group
 * and bow style. Fewer targets removes the ones above (with a warning if any are scored); more targets adds
 * unscored ones and reopens a finished round.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRoundScreen(vm: EditRoundViewModel, onBack: () -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Round details")
                        form?.groupName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            val f = form
            BottomActionBar(text = "Save", enabled = f != null && f.targetCount != null && f.changed && !f.startTargetError) {
                if (f != null && f.scoredRemoved.isNotEmpty()) confirmRemove = true else vm.save(onBack)
            }
        },
    ) { padding ->
        val f = form
        if (f == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                title = "Shoot",
                subtitle = if (f.groupName != null) "Group round: the date and number of targets change for everyone." else null,
            ) {
                DateField(
                    label = "Shoot date",
                    date = f.shootDate,
                    onDate = { d -> if (d != null) vm.update { it.copy(shootDate = d) } },
                )
                val countError = f.targetCountText.isNotEmpty() && f.targetCount == null
                NumberField(
                    label = "Number of targets",
                    value = f.targetCountText,
                    onValue = { v -> vm.update { it.copy(targetCountText = v) } },
                    isError = countError,
                    supportingText = when {
                        countError -> "1 to ${RoundRepository.MAX_TARGETS}"
                        f.scoredRemoved.isNotEmpty() -> "Removes scored target${if (f.scoredRemoved.size == 1) "" else "s"} " +
                            f.scoredRemoved.joinToString(", ")
                        f.reopens -> "Adds unscored targets and reopens the round"
                        else -> null
                    },
                )
                val startError = f.startTargetError
                NumberField(
                    label = "Starting target (optional)",
                    value = f.startTargetText,
                    onValue = { v -> vm.update { it.copy(startTargetText = v) } },
                    isError = startError,
                    supportingText = if (startError) "1 to ${f.targetCount}"
                    else if (f.groupName != null) "The same for everyone in the group." else "The first target shot, if not 1.",
                )
            }

            SectionCard(
                title = "Archer",
                subtitle = if (f.groupName != null && f.groupName != "You") "Only changes ${f.groupName}'s round."
                else "Only changes this round, not your profile.",
            ) {
                SegmentedChoice(ArcherClass.entries, f.archerClass, { it.displayName }) { c ->
                    vm.update { it.copy(archerClass = c) }
                }
                OptionDropdown(
                    label = "Age group",
                    options = AgeGroup.entries,
                    selected = f.ageGroup,
                    optionLabel = { it.displayName },
                    onSelect = { g -> if (g != null) vm.update { it.copy(ageGroup = g) } },
                )
                OptionDropdown(
                    label = "Bow style",
                    options = BowStyle.entries,
                    selected = f.bowStyle,
                    optionLabel = { it.label },
                    onSelect = { b -> if (b != null) vm.update { it.copy(bowStyle = b) } },
                )
            }

            Text(
                "Scores aren't changed. A finished round uploads again with the new details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val f = form
    if (confirmRemove && f != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove scored targets?") },
            text = {
                Text(
                    "Going down to ${f.targetCount} targets removes the scores for target" +
                        "${if (f.scoredRemoved.size == 1) "" else "s"} ${f.scoredRemoved.joinToString(", ")}. This can't be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.save(onBack) }) { Text("Remove and save") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
