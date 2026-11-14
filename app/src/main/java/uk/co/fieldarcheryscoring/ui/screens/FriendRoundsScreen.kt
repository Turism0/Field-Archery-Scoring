package uk.co.fieldarcheryscoring.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fieldarcheryscoring.data.FriendRoundSummary
import uk.co.fieldarcheryscoring.ui.FriendRoundsViewModel
import uk.co.fieldarcheryscoring.ui.Load
import uk.co.fieldarcheryscoring.ui.components.UkDateLong

/** A friend's finished rounds, newest first; tap one for their scorecard. Remove friend in the ⋮ menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendRoundsScreen(
    vm: FriendRoundsViewModel,
    onBack: () -> Unit,
    onOpenRound: (FriendRoundSummary) -> Unit,
) {
    val rounds by vm.rounds.collectAsStateWithLifecycle()
    val handicaps by vm.handicaps.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.username)
                        Text("Finished rounds", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Remove friend") }, onClick = { menuOpen = false; confirmRemove = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.load(showSpinner = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val r = rounds) {
                Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(r.message, color = MaterialTheme.colorScheme.onErrorContainer)
                                FilledTonalButton(onClick = { vm.load() }) { Text("Try again") }
                            }
                        }
                    }
                }
                is Load.Ready -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (r.value.isEmpty()) {
                        item {
                            Text(
                                "${vm.username} hasn't finished any rounds yet.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Their personal handicaps (2.9).
                    if (handicaps.isNotEmpty()) item(key = "handicap") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Handicap", style = MaterialTheme.typography.titleMedium)
                                handicaps.forEach { h ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(h.bowStyle.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                        Text("${h.handicap}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                                val first = handicaps.first()
                                Text(
                                    "Par 960 (40 targets) minus their usual score: the best ${first.counted.size} of their last ${first.considered} " +
                                        "${first.bowStyle.displayName} rounds" + (if (handicaps.size > 1) ", and the same for each bow." else "."),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    items(r.value, key = { it.roundId }) { round ->
                        Card(
                            onClick = { onOpenRound(round) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(round.shootDate.format(UkDateLong), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${round.classLine} · ${round.targetsLine}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    round.locationName?.let {
                                        Text(it, style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${round.totalPoints}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                    Text("points", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${vm.username}?") },
            text = { Text("You'll stop seeing each other's rounds. You can send a new request later.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeFriend(onBack) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
