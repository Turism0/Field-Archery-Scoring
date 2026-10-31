package uk.co.fnurble.nfas.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.LocalDate
import java.time.Instant
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import uk.co.fnurble.nfas.data.local.SyncState
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.data.RoundSummary
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.ui.HomeViewModel
import uk.co.fnurble.nfas.ui.components.UkDateLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onProfile: () -> Unit,
    onNewRound: () -> Unit,
    /** Finished rounds open on the scorecard, rounds in progress on the scoring screen. */
    onOpenRound: (RoundSummary) -> Unit,
) {
    val rounds by vm.rounds.collectAsStateWithLifecycle()
    val profileReady by vm.profileReady.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Big Game rounds") },
                actions = {
                    IconButton(onClick = onProfile) { Icon(Icons.Filled.Person, contentDescription = "Profile") }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = if (profileReady) onNewRound else onProfile,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New round") },
            )
        },
    ) { padding ->
        // Pull down anywhere on the list to sync now (upload waiting rounds, download changes).
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val list = rounds
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                // Scrollable even when empty, so pull-to-refresh still works (e.g. a new phone before the first sync).
                list.isEmpty() -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (signedIn) SyncStatusLine(lastSync)
                    Spacer(Modifier.height(16.dp))
                    Text("No rounds yet", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (profileReady) "Tap New round to start scoring." else "Set up your profile first: class and main bow style.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (!profileReady) {
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(onClick = onProfile) { Text("Set up profile") }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (signedIn) item(key = "sync") { SyncStatusLine(lastSync) }
                    items(list, key = { it.id }) { r -> RoundCard(r, showSync = signedIn) { onOpenRound(r) } }
                }
            }
        }
    }
}

/** "Last synced 21:45 · pull down to sync" (time only for today, date and time otherwise). */
@Composable
private fun SyncStatusLine(lastSync: Instant?) {
    val text = if (lastSync == null) {
        "Not synced yet · pull down to sync"
    } else {
        val local = lastSync.atZone(ZoneId.systemDefault())
        val today = local.toLocalDate() == LocalDate.now()
        val pattern = if (today) "HH:mm" else "d MMM HH:mm"
        "Last synced ${local.format(DateTimeFormatter.ofPattern(pattern))} · pull down to sync"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RoundCard(r: RoundSummary, showSync: Boolean, onClick: () -> Unit) {
    val inProgress = r.status == RoundStatus.IN_PROGRESS
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (inProgress) scheme.secondaryContainer else scheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (inProgress) {
                    Surface(shape = MaterialTheme.shapes.small, color = scheme.primary, contentColor = scheme.onPrimary) {
                        Text(
                            "IN PROGRESS",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(r.shootDate.format(UkDateLong), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${r.classLine} · ${r.targetCount} targets",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                // Finished rounds upload when signed in; in-progress ones stay on the phone until finished.
                if (showSync && !inProgress) SyncLabel(r.syncState)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${r.totalPoints}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("points", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SyncLabel(state: SyncState) {
    val scheme = MaterialTheme.colorScheme
    val (icon, text, colour) = when (state) {
        SyncState.SYNCED -> Triple(Icons.Filled.Check, "Backed up", scheme.primary)
        SyncState.PENDING -> Triple(Icons.Filled.Refresh, "Waiting to upload", scheme.onSurfaceVariant)
        SyncState.FAILED -> Triple(Icons.Filled.Warning, "Couldn't upload", scheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = colour)
    }
}
