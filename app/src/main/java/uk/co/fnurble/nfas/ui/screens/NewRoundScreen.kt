package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.data.RoundRepository
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.domain.RoundType
import uk.co.fnurble.nfas.ui.NewRoundViewModel
import uk.co.fnurble.nfas.ui.components.BottomActionBar
import uk.co.fnurble.nfas.ui.components.DateField
import uk.co.fnurble.nfas.ui.components.NumberField
import uk.co.fnurble.nfas.ui.components.OptionDropdown
import uk.co.fnurble.nfas.ui.components.SectionCard
import uk.co.fnurble.nfas.ui.components.SegmentedChoice

/** Quick picks for the number of targets; any number can still be typed. */
private val CommonTargetCounts = listOf(18, 20, 36, 40)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRoundScreen(
    vm: NewRoundViewModel,
    roundType: RoundType,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onStarted: (String) -> Unit,
) {
    val form by vm.form.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("New round")
                        Text(roundType.displayName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onEditProfile) { Icon(Icons.Filled.Edit, contentDescription = "Edit profile") }
                },
            )
        },
        bottomBar = {
            BottomActionBar(text = "Start round", enabled = form?.isValid == true) { vm.start(onStarted) }
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
            SectionCard(title = "Shoot") {
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
                    supportingText = if (countError) "1 to ${RoundRepository.MAX_TARGETS}" else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommonTargetCounts.forEach { n ->
                        SuggestionChip(
                            onClick = { vm.update { it.copy(targetCountText = n.toString()) } },
                            label = { Text("$n") },
                        )
                    }
                }
            }

            SectionCard(title = "Archer", subtitle = "Filled in from your profile. Changes here only affect this round.") {
                SegmentedChoice(ArcherClass.entries, f.archerClass, { it.displayName }) { c ->
                    vm.update { it.copy(archerClass = c) }
                }
                OptionDropdown(
                    label = "Age group",
                    options = AgeGroup.entries,
                    selected = f.ageGroup,
                    optionLabel = { it.displayName },
                    onSelect = { g -> vm.update { it.copy(ageGroupChoice = g) } },
                    supportingText = f.ageGroupSource.replaceFirstChar { it.uppercase() },
                )
                OptionDropdown(
                    label = "Bow style",
                    options = BowStyle.entries,
                    selected = f.bowStyle,
                    optionLabel = { it.label },
                    onSelect = { b -> vm.update { it.copy(bowStyle = b) } },
                    supportingText = if (f.bowStyle != null && f.bowStyle == f.profile.mainBowStyle) "Your main style" else null,
                )
            }

            if (!f.isValid) {
                Text(
                    "Fill in the number of targets, class, age group and bow style to start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
