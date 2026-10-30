package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.data.RoundSummary
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.ui.HomeViewModel
import uk.co.fnurble.nfas.ui.components.UkDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onProfile: () -> Unit,
    onNewRound: () -> Unit,
    onOpenRound: (String) -> Unit,
) {
    val rounds by vm.rounds.collectAsStateWithLifecycle()
    val profileReady by vm.profileReady.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NFAS Big Game") },
                actions = {
                    IconButton(onClick = onProfile) { Icon(Icons.Filled.Person, contentDescription = "Profile") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = if (profileReady) onNewRound else onProfile,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New round") },
            )
        },
    ) { padding ->
        val list = rounds
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No rounds yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (!profileReady) {
                    Text("Set up your profile first (class and main bow style).")
                    TextButton(onClick = onProfile) { Text("Set up profile") }
                } else {
                    Text("Tap New round to start scoring.")
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { r -> RoundCard(r) { onOpenRound(r.id) } }
            }
        }
    }
}

@Composable
private fun RoundCard(r: RoundSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.shootDate.format(UkDate), style = MaterialTheme.typography.titleMedium)
                Text("${r.classLine} · ${r.targetCount} targets", style = MaterialTheme.typography.bodyMedium)
                if (r.status == RoundStatus.IN_PROGRESS) {
                    AssistChip(onClick = onClick, label = { Text("In progress") })
                }
            }
            Text("${r.totalPoints}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}
