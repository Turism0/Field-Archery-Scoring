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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.domain.resolveAgeGroup
import uk.co.fnurble.nfas.ui.ProfileViewModel
import uk.co.fnurble.nfas.ui.components.ChipChoice
import uk.co.fnurble.nfas.ui.components.DateField
import uk.co.fnurble.nfas.ui.components.OptionDropdown
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val profile by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val p = profile
        if (p == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Stored on this phone only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = p.realName.orEmpty(),
                onValueChange = { v -> vm.update { it.copy(realName = v) } },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DateField(
                label = "Date of birth (optional)",
                date = p.dateOfBirth,
                onDate = { d -> vm.update { it.copy(dateOfBirth = d) } },
                allowClear = true,
                supportingText = "Used to work out your age group on each shoot day",
            )

            Text("Shared when syncing (added in a later version)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = p.username.orEmpty(),
                onValueChange = { v -> vm.update { it.copy(username = v) } },
                label = { Text("Username") },
                supportingText = { Text("Don't use your real name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Class", style = MaterialTheme.typography.titleSmall)
            ChipChoice(ArcherClass.entries, p.archerClass, { it.displayName }) { c -> vm.update { it.copy(archerClass = c) } }

            val fromDob = resolveAgeGroup(null, p.dateOfBirth, LocalDate.now())
            OptionDropdown(
                label = "Age group",
                options = AgeGroup.entries,
                selected = p.ageGroupManual,
                optionLabel = { it.displayName },
                onSelect = { g -> vm.update { it.copy(ageGroupManual = g) } },
                noneLabel = if (fromDob != null) "Automatic (${fromDob.displayName} today)" else "Not set",
                supportingText = "Leave on automatic to work it out from your date of birth",
            )

            OptionDropdown(
                label = "Main bow style",
                options = BowStyle.entries,
                selected = p.mainBowStyle,
                optionLabel = { it.label },
                onSelect = { b -> vm.update { it.copy(mainBowStyle = b) } },
                noneLabel = "Not set",
            )

            Button(onClick = { vm.save(onBack) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}
