package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.domain.AccountRules
import uk.co.fnurble.nfas.ui.AccountStep
import uk.co.fnurble.nfas.ui.AccountViewModel
import uk.co.fnurble.nfas.ui.components.SectionCard

/**
 * Sign in or create an account:
 *  1. enter a username → the server checks it;
 *  2a. it exists → "That username already exists" → password → Sign in;
 *  2b. it's new → choose a password, typed twice → Create account.
 * Returns to the previous screen once signed in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(vm: AccountViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(s.signedIn) { if (s.signedIn) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.step == AccountStep.NEW_USER) "Create account" else "Sign in") },
                navigationIcon = {
                    IconButton(onClick = { if (s.step == AccountStep.USERNAME) onBack() else vm.changeUsername() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (s.step) {
                AccountStep.USERNAME -> SectionCard(
                    title = "Username",
                    subtitle = "Enter your username to sign in, or choose one to create an account.",
                ) {
                    OutlinedTextField(
                        value = s.username,
                        onValueChange = vm::onUsername,
                        label = { Text("Username") },
                        supportingText = { Text(AccountRules.USERNAME_HELP) },
                        singleLine = true,
                        enabled = !s.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { vm.continueWithUsername() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BusyButton("Continue", s.busy, vm::continueWithUsername)
                }

                AccountStep.EXISTING_USER -> SectionCard(
                    title = "That username already exists",
                    subtitle = "If ${s.username} is your account, enter your password.",
                ) {
                    PasswordField("Password", s.password, vm::onPassword, !s.busy, ImeAction.Done) { vm.signIn() }
                    BusyButton("Sign in", s.busy, vm::signIn)
                    TextButton(onClick = vm::changeUsername, enabled = !s.busy) { Text("Use a different username") }
                }

                AccountStep.NEW_USER -> SectionCard(
                    title = "${s.username} is available",
                    subtitle = "Choose a password to create your account.",
                ) {
                    PasswordField("Password", s.password, vm::onPassword, !s.busy, ImeAction.Next, null)
                    PasswordField("Type the password again", s.confirmPassword, vm::onConfirmPassword, !s.busy, ImeAction.Done) {
                        vm.createAccount()
                    }
                    Text(
                        "At least ${AccountRules.MIN_PASSWORD} characters. There's no password reset yet, so keep it somewhere safe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BusyButton("Create account", s.busy, vm::createAccount)
                    TextButton(onClick = vm::changeUsername, enabled = !s.busy) { Text("Use a different username") }
                }
            }

            s.error?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BusyButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    enabled: Boolean,
    imeAction: ImeAction,
    onDone: (() -> Unit)?,
) {
    var show by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
