package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.data.AdminLocation
import uk.co.fnurble.nfas.domain.metresBetween
import uk.co.fnurble.nfas.ui.AdminLocationsViewModel
import uk.co.fnurble.nfas.ui.Load
import uk.co.fnurble.nfas.ui.components.SectionCard
import uk.co.fnurble.nfas.ui.components.UkDate
import java.time.ZoneId
import java.util.Locale

/**
 * Admin → Shoot locations (Admins and the Owner): every live location with how many rounds use it.
 * Tap one to rename it, or merge it (a duplicate) into the location to keep. Pull down to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminLocationsScreen(vm: AdminLocationsViewModel, onBack: () -> Unit) {
    val role by vm.role.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<AdminLocation?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    LaunchedEffect(role) { if (!role.isAdmin) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shoot locations") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro") {
                    SectionCard(
                        title = "Locations",
                        subtitle = "Tap a location to fix its name, or to merge a duplicate (e.g. two phones named the same club " +
                            "while offline) into the one to keep. Its rounds move across.",
                    ) {}
                }
                when (val l = list) {
                    Load.Loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    is Load.Failed -> item(key = "failed") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(l.message, color = MaterialTheme.colorScheme.onErrorContainer)
                                FilledTonalButton(onClick = { vm.load() }) { Text("Try again") }
                            }
                        }
                    }
                    is Load.Ready -> {
                        if (l.value.isEmpty()) {
                            item(key = "none") {
                                Text("No shoot locations yet. They appear as archers name them.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(l.value, key = { it.id }) { loc ->
                            LocationCard(loc) { selected = loc }
                        }
                    }
                }
            }
        }
    }

    val all = (list as? Load.Ready)?.value.orEmpty()
    selected?.let { loc ->
        LocationActions(
            location = loc,
            others = all.filter { it.id != loc.id },
            busy = busy,
            onRename = { name -> vm.rename(loc, name) { selected = null } },
            onMerge = { keep -> vm.merge(loc, keep) { selected = null } },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun LocationCard(loc: AdminLocation, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(loc.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        "${loc.rounds} round${if (loc.rounds == 1) "" else "s"}",
                        loc.createdBy?.let { "named by $it" },
                        loc.createdUtc?.let { it.atZone(ZoneId.systemDefault()).toLocalDate().format(UkDate) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** Rename, or pick the location to merge this one into (nearest first), then confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationActions(
    location: AdminLocation,
    others: List<AdminLocation>,
    busy: Boolean,
    onRename: (String) -> Unit,
    onMerge: (AdminLocation) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(location.id) { mutableStateOf(location.name) }
    var picking by remember(location.id) { mutableStateOf(false) }
    var confirmInto by remember(location.id) { mutableStateOf<AdminLocation?>(null) }
    val nearestFirst = remember(location.id, others) {
        others.map { it to metresBetween(location.latitude, location.longitude, it.latitude, it.longitude) }
            .sortedBy { it.second }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(location.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${location.rounds} round${if (location.rounds == 1) "" else "s"}" + (location.createdBy?.let { " · named by $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!picking) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onRename(name) },
                    enabled = !busy && name.trim().length >= 2 && name.trim() != location.name,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save name") }
                OutlinedButton(
                    onClick = { picking = true },
                    enabled = !busy && others.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Merge into another location…") }
            } else {
                Text("Merge into which location? Nearest first.", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(nearestFirst, key = { it.first.id }) { (other, metres) ->
                        ListItem(
                            headlineContent = { Text(other.name) },
                            supportingContent = { Text("${distance(metres)} · ${other.rounds} round${if (other.rounds == 1) "" else "s"}") },
                            leadingContent = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                            modifier = Modifier.clickable(enabled = !busy) { confirmInto = other },
                        )
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = { picking = false }) { Text("Back") }
            }
        }
    }

    confirmInto?.let { keep ->
        AlertDialog(
            onDismissRequest = { confirmInto = null },
            title = { Text("Merge locations?") },
            text = {
                Text(
                    "${location.name} will be merged into ${keep.name}. Its ${location.rounds} round" +
                        "${if (location.rounds == 1) "" else "s"} move to ${keep.name} and ${location.name} disappears. " +
                        "This can't be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmInto = null; onMerge(keep) }, enabled = !busy) { Text("Merge") } },
            dismissButton = { TextButton(onClick = { confirmInto = null }) { Text("Cancel") } },
        )
    }
}

private fun distance(metres: Double): String =
    if (metres < 1000) "${metres.toInt()} m away" else String.format(Locale.UK, "%.1f km away", metres / 1000)
