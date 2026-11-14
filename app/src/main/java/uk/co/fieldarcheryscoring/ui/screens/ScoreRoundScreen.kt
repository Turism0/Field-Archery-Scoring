package uk.co.fieldarcheryscoring.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fieldarcheryscoring.data.RoundSummary
import uk.co.fieldarcheryscoring.ui.ScoreRoundViewModel
import uk.co.fieldarcheryscoring.ui.components.RoundCard

/** Continue an unfinished round, or start a new one (next: choose the round type). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreRoundScreen(
    vm: ScoreRoundViewModel,
    onBack: () -> Unit,
    onContinue: (RoundSummary) -> Unit,
    onStartNew: () -> Unit,
    onProfile: () -> Unit,
    /** Join someone else's live group (code or QR). */
    onJoin: () -> Unit = {},
) {
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val profileReady by vm.profileReady.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Score a round") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val list = inProgress
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "new") {
                if (profileReady) {
                    Button(onClick = onStartNew, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("  Start a new round", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Set up your profile first", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Your class and main bow style are filled in on each new round.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FilledTonalButton(onClick = onProfile) { Text("Set up profile") }
                        }
                    }
                }
            }
            if (profileReady) {
                item(key = "join") {
                    OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("Join a live group") }
                }
            }
            if (list.isNotEmpty()) {
                item(key = "continueHeader") {
                    Text(
                        "Continue a round",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(list, key = { it.id }) { r -> RoundCard(r, showSync = false) { onContinue(r) } }
            }
        }
    }
}
