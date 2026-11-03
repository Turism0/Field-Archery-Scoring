package uk.co.fnurble.fieldarchery.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.fieldarchery.data.AdminLocation
import uk.co.fnurble.fieldarchery.data.DuplicatePair
import uk.co.fnurble.fieldarchery.ui.AdminDuplicatesViewModel
import uk.co.fnurble.fieldarchery.ui.Load
import uk.co.fnurble.fieldarchery.ui.components.SectionCard
import java.util.Locale

/**
 * Admin → Possible duplicates: pairs of locations within a quarter mile of each other, or with the
 * same or a similar name. Merge one into the other, or approve them as different places so they stop
 * being flagged. Pull down to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDuplicatesScreen(vm: AdminDuplicatesViewModel, onBack: () -> Unit) {
    val role by vm.role.collectAsStateWithLifecycle()
    val pairs by vm.pairs.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmMerge by remember { mutableStateOf<Pair<AdminLocation, AdminLocation>?>(null) }

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
                title = { Text("Possible duplicates") },
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
                        title = "Check these",
                        subtitle = "Locations within a quarter mile of each other, or with the same or a similar name. " +
                            "Merge the duplicate into the one to keep, or tap \"Different places\" so they stop showing.",
                    ) {}
                }
                when (val p = pairs) {
                    Load.Loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    is Load.Failed -> item(key = "failed") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(p.message, color = MaterialTheme.colorScheme.onErrorContainer)
                                FilledTonalButton(onClick = { vm.load() }) { Text("Try again") }
                            }
                        }
                    }
                    is Load.Ready -> {
                        if (p.value.isEmpty()) {
                            item(key = "none") {
                                Text("Nothing to check. All locations look distinct.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(p.value, key = { it.a.id + it.b.id }) { pair ->
                            PairCard(
                                pair = pair,
                                busy = busy,
                                onMergeAIntoB = { confirmMerge = pair.a to pair.b },
                                onMergeBIntoA = { confirmMerge = pair.b to pair.a },
                                onApprove = { vm.approve(pair) },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmMerge?.let { (duplicate, keep) ->
        AlertDialog(
            onDismissRequest = { confirmMerge = null },
            title = { Text("Merge locations?") },
            text = {
                Text(
                    "${duplicate.name} will be merged into ${keep.name}. Its ${duplicate.rounds} round" +
                        "${if (duplicate.rounds == 1) "" else "s"} move to ${keep.name} and ${duplicate.name} disappears. " +
                        "This can't be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmMerge = null; vm.merge(duplicate, keep) }) { Text("Merge") } },
            dismissButton = { TextButton(onClick = { confirmMerge = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PairCard(
    pair: DuplicatePair,
    busy: Boolean,
    onMergeAIntoB: () -> Unit,
    onMergeBIntoA: () -> Unit,
    onApprove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                pair.reasons.joinToString(" · ") {
                    when (it) {
                        "nearby" -> "Nearby"
                        "same_name" -> "Same name"
                        "similar_name" -> "Similar name"
                        else -> it
                    }
                } + " · " + distance(pair.distanceMetres) + " apart",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            LocationLine(pair.a)
            HorizontalDivider()
            LocationLine(pair.b)
            // A private location can be merged into the public one, never the other way (that would hide everyone's rounds).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMergeAIntoB, enabled = !busy && !pair.b.isPrivate, modifier = Modifier.weight(1f)) { Text("Keep 2nd", maxLines = 1) }
                OutlinedButton(onClick = onMergeBIntoA, enabled = !busy && !pair.a.isPrivate, modifier = Modifier.weight(1f)) { Text("Keep 1st", maxLines = 1) }
            }
            if (pair.a.isPrivate || pair.b.isPrivate) {
                Text(
                    "Private locations are only shown to Admins when they clash with a public one. Keep the public one to " +
                        "merge the private one into it, or mark them as different places.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onApprove, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Different places – stop flagging")
            }
        }
    }
}

@Composable
private fun LocationLine(loc: AdminLocation) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(loc.name, style = MaterialTheme.typography.titleMedium)
            if (loc.isPrivate) PrivateBadge()
        }
        Text(
            listOfNotNull(
                "${loc.rounds} round${if (loc.rounds == 1) "" else "s"}",
                loc.createdBy?.let { "named by $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun distance(metres: Int): String =
    if (metres < 1000) "$metres m" else String.format(Locale.UK, "%.1f km", metres / 1000.0)
