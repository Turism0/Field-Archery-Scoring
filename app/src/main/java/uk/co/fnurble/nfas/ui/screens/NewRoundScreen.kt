package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.data.RoundRepository
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.ui.NewRoundViewModel
import uk.co.fnurble.nfas.ui.components.ChipChoice
import uk.co.fnurble.nfas.ui.components.DateField
import uk.co.fnurble.nfas.ui.components.NumberField
import uk.co.fnurble.nfas.ui.components.OptionDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRoundScreen(
    vm: NewRoundViewModel,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onStarted: (String) -> Unit,
) {
    val form by vm.form.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New round") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val f = form
        if (f == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                supportingText = if (countError) "1 to ${RoundRepository.MAX_TARGETS}" else null,
            )

            Text("Class", style = MaterialTheme.typography.titleSmall)
            ChipChoice(ArcherClass.entries, f.archerClass, { it.displayName }) { c -> vm.update { it.copy(archerClass = c) } }

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

            Button(
                onClick = { vm.start(onStarted) },
                enabled = f.isValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start round") }

            TextButton(onClick = onEditProfile) { Text("Edit profile") }
        }
    }
}
