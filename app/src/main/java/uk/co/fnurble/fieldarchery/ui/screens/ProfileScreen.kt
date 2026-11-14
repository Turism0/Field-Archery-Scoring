package uk.co.fnurble.fieldarchery.ui.screens

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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.domain.resolveAgeGroup
import uk.co.fnurble.fieldarchery.ui.ProfileViewModel
import uk.co.fnurble.fieldarchery.ui.components.BottomActionBar
import uk.co.fnurble.fieldarchery.ui.components.DateField
import uk.co.fnurble.fieldarchery.ui.components.OptionDropdown
import uk.co.fnurble.fieldarchery.ui.components.RecoveryCodeDialog
import uk.co.fnurble.fieldarchery.ui.components.SectionCard
import uk.co.fnurble.fieldarchery.ui.components.SegmentedChoice
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel, onBack: () -> Unit, onAccount: () -> Unit) {
    val profile by vm.state.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    /** What removing your rounds at sign-out would lose, worked out when Sign out is tapped. */
    var signOutLoss by remember { mutableStateOf<String?>(null) }
    /** 3.2.09: also delete my rounds from this phone (otherwise they're put away until I sign in again). */
    var removeToo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val recovery by vm.recovery.collectAsStateWithLifecycle()
    var askPassword by remember { mutableStateOf(false) }
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

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
                        "Sign in to back up your rounds to the server and, later, share scorecards with friends.",
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
                        OutlinedButton(onClick = { scope.launch { signOutLoss = vm.signOutWarning(); removeToo = false; confirmSignOut = true } }) { Text("Sign out") }
                    }
                    // Password reset with no email (2.4): a recovery code to keep somewhere safe.
                    Text(
                        "Lost your recovery code, or made your account before there was one? Make a new one (the old one " +
                            "stops working).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { askPassword = true }, enabled = !recovery.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (recovery.busy) "Making a recovery code…" else "New recovery code")
                    }
                    recovery.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    // Google Play: accounts can be deleted in the app (2.4.02); also at fieldarcheryscoring.fnurble.co.uk/delete-account.
                    TextButton(onClick = { confirmDelete = true }, enabled = !deleting.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (deleting.busy) "Deleting…" else "Delete account", color = MaterialTheme.colorScheme.error)
                    }
                    deleting.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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

    recovery.code?.let { RecoveryCodeDialog(it, onDone = vm::recoveryDone) }
    if (deleted) {
        AlertDialog(
            onDismissRequest = vm::deletedShown,
            title = { Text("Account deleted") },
            text = { Text("Your account and everything the server kept about it have been deleted. Your rounds are still on this phone.") },
            confirmButton = { TextButton(onClick = vm::deletedShown) { Text("OK") } },
        )
    }
    if (confirmDelete) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This deletes your account and everything the server keeps about it: your rounds and scores there, " +
                            "friends, round requests and your place in live groups. It can't be undone. Rounds on this " +
                            "phone stay here.",
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteAccount(password) }, enabled = password.isNotEmpty()) {
                    Text("Delete account", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (askPassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("New recovery code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your password to make a new recovery code.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { askPassword = false; vm.newRecoveryCode(password) }, enabled = password.isNotEmpty()) { Text("Make code") }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your rounds, profile and Stats choices are put away on this phone: nobody else signing in here sees " +
                            "them, and they're all back when you sign in again.",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = removeToo, onCheckedChange = { removeToo = it })
                        Text("Also remove my rounds from this phone")
                    }
                    if (removeToo) {
                        Text(
                            "They come back from the server when you sign in again; your real name and date of birth don't (they never leave the phone).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        signOutLoss?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = {
                val warn = removeToo && signOutLoss != null
                TextButton(onClick = { confirmSignOut = false; vm.signOut(remove = removeToo, onDone = onBack) }) {
                    Text(if (warn) "Sign out anyway" else "Sign out", color = if (warn) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}
