package uk.co.fnurble.fieldarchery.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.fieldarchery.data.RoundSummary
import uk.co.fnurble.fieldarchery.domain.PersonalStats.Period
import uk.co.fnurble.fieldarchery.ui.HistoryChoices
import uk.co.fnurble.fieldarchery.ui.HistoryFilter
import uk.co.fnurble.fieldarchery.ui.HistoryViewModel
import uk.co.fnurble.fieldarchery.ui.components.DateField
import uk.co.fnurble.fieldarchery.ui.components.FilterMenuChip
import uk.co.fnurble.fieldarchery.ui.components.RoundCard
import uk.co.fnurble.fieldarchery.ui.components.SyncStatusLine
import uk.co.fnurble.fieldarchery.ui.components.UkDate

/** Finished rounds, newest first, with filters and PB marks. Tap one for its scorecard; pull down to sync. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit, onOpenRound: (RoundSummary) -> Unit) {
    val rounds by vm.rounds.collectAsStateWithLifecycle()
    val total by vm.totalCount.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val choices by vm.choices.collectAsStateWithLifecycle()
    val pbs by vm.personalBests.collectAsStateWithLifecycle()
    val nets by vm.nets.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickDates by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Score history") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val list = rounds
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                total == 0 -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (signedIn) SyncStatusLine(lastSync)
                    Spacer(Modifier.height(48.dp))
                    Text("No finished rounds yet", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Rounds appear here once you finish them.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (signedIn) item(key = "sync") { SyncStatusLine(lastSync) }
                    item(key = "filters") {
                        HistoryFilters(filter, choices, onChange = vm::setFilter, onPickDates = { pickDates = true })
                    }
                    if (filter.isActive) item(key = "count") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Showing ${list.size} of $total round${if (total == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = vm::clearFilter) { Text("Clear filters") }
                        }
                    }
                    if (list.isEmpty()) item(key = "none") {
                        Text(
                            "No rounds match these filters.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    }
                    items(list, key = { it.id }) { r ->
                        RoundCard(r, showSync = signedIn, personalBest = r.id in pbs, net = nets[r.id]) { onOpenRound(r) }
                    }
                }
            }
        }
    }

    if (pickDates) {
        val current = filter.period as? Period.Between
        var from by remember { mutableStateOf(current?.from) }
        var to by remember { mutableStateOf(current?.to) }
        AlertDialog(
            onDismissRequest = { pickDates = false },
            title = { Text("Rounds between") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateField(label = "From", date = from, onDate = { from = it }, allowClear = true)
                    DateField(label = "To", date = to, onDate = { to = it }, allowClear = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setFilter { it.copy(period = if (from == null && to == null) Period.All else Period.Between(from, to)) }
                    pickDates = false
                }) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Cancel") } },
        )
    }
}

/** Round type (only if you've shot more than one), bow, location and period; each lists only what's in your rounds. */
@Composable
private fun HistoryFilters(
    f: HistoryFilter,
    c: HistoryChoices,
    onChange: ((HistoryFilter) -> HistoryFilter) -> Unit,
    onPickDates: () -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (c.types.size > 1 || f.type != null) {
            FilterMenuChip("Type", "All", c.types, f.type, { it.displayName }, { t -> onChange { it.copy(type = t) } })
        }
        FilterMenuChip("Bow", "All", c.bowStyles, f.bowStyle, { it.label }, { b -> onChange { it.copy(bowStyle = b) } })
        if (c.locations.isNotEmpty() || f.location != null) {
            FilterMenuChip("Location", "All", c.locations, f.location, { it }, { l -> onChange { it.copy(location = l) } })
        }
        val periods = listOf<Period>(Period.Last12Months) + c.years.map { Period.InYear(it) }
        FilterMenuChip(
            title = "When",
            anyLabel = Period.All.label,
            options = periods,
            selected = f.period.takeUnless { it == Period.All },
            optionLabel = { it.label },
            onSelect = { p -> onChange { it.copy(period = p ?: Period.All) } },
            extra = listOf("Choose dates…" to onPickDates),
            selectedText = (f.period as? Period.Between)?.let { b ->
                when {
                    b.from != null && b.to != null -> "${b.from.format(UkDate)} – ${b.to.format(UkDate)}"
                    b.from != null -> "From ${b.from.format(UkDate)}"
                    else -> "To ${b.to?.format(UkDate)}"
                }
            },
        )
    }
}
