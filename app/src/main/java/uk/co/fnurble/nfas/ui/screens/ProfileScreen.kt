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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.domain.resolveAgeGroup
import uk.co.fnurble.nfas.ui.ProfileViewModel
import uk.co.fnurble.nfas.ui.components.BottomActionBar
import uk.co.fnurble.nfas.ui.components.DateField
import uk.co.fnurble.nfas.ui.components.OptionDropdown
import uk.co.fnurble.nfas.ui.components.SectionCard
import uk.co.fnurble.nfas.ui.components.SegmentedChoice
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel, onBack: () -> Unit, onAccount: () -> Unit) {
    val profile by vm.state.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = { BottomActionBar(text = "Save", enabled = profile != null) { vm.save(onBack) } },
    ) { padding ->
        val p = profile
        if (p == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Account", subtitle = "Optional. Scoring works without it.") {
                val a = account
                if (a == null) {
                    Text(
                        "Sign in to back up your rounds to the NFAS server and, later, share scorecards with friends.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onAccount, modifier = Modifier.fillMaxWidth()) { Text("Sign in or create account") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Signed in as", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(a.username, style = MaterialTheme.typography.titleLarge)
                        }
                        OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
                    }
                }
            }

            SectionCard(title = "About you", subtitle = "Stored on this phone only. Never sent anywhere.") {
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
            }

            SectionCard(title = "Archery", subtitle = "Copied onto each round, and shared with your scores when signed in.") {
                SegmentedChoice(ArcherClass.entries, p.archerClass, { it.displayName }) { c ->
                    vm.update { it.copy(archerClass = c) }
                }
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
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your rounds stay on this phone. Sign in again any time with your username and password.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}
