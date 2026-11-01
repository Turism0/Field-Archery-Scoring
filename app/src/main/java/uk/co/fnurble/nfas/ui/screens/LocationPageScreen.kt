package uk.co.fnurble.nfas.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.data.BowStyleStats
import uk.co.fnurble.nfas.data.FriendRoundSummary
import uk.co.fnurble.nfas.data.LocationFriend
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.ui.Load
import uk.co.fnurble.nfas.ui.LocationPageViewModel
import uk.co.fnurble.nfas.ui.components.UkDateLong
import java.util.Locale

/**
 * A shoot location: your scorecards there, friends' scorecards and stats there, and everyone's stats per
 * bow style. Private locations only show your own rounds. Pull down to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPageScreen(
    vm: LocationPageViewModel,
    onBack: () -> Unit,
    onOpenMyRound: (roundId: String) -> Unit,
    onOpenFriendRound: (friend: LocationFriend, round: FriendRoundSummary) -> Unit,
) {
    val page by vm.page.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val title = (page as? Load.Ready)?.value?.location?.name ?: "Shoot location"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.load(showSpinner = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val p = page) {
                Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.message, color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = { vm.load() }) { Text("Try again") }
                }
                is Load.Ready -> {
                    val data = p.value
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (data.location.isPrivate) {
                            item(key = "private") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PrivateBadge()
                                    Text("Only you and admins can see this location.", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        // ---- Yours ----
                        item(key = "mineHeader") { Header("Your scorecards here") }
                        if (data.myRounds.isEmpty()) {
                            item(key = "mineNone") { Muted("You haven't finished a round here yet.") }
                        } else {
                            item(key = "mineSummary") {
                                Muted(summary(data.myRounds.size, data.myRounds.maxOf { it.totalPoints }))
                            }
                            items(data.myRounds, key = { "me-" + it.roundId }) { r -> RoundRow(r) { onOpenMyRound(r.roundId) } }
                        }

                        if (!data.location.isPrivate) {
                            // ---- Friends ----
                            item(key = "friendsHeader") { Header("Friends here") }
                            if (data.friends.isEmpty()) {
                                item(key = "friendsNone") { Muted("None of your friends have finished a round here yet.") }
                            }
                            items(data.friends, key = { "f-" + it.archerId }) { f -> FriendCard(f) { r -> onOpenFriendRound(f, r) } }

                            // ---- Everyone ----
                            item(key = "allHeader") { Header("All archers, by bow style") }
                            if (data.bowStyles.isEmpty()) {
                                item(key = "allNone") { Muted("No finished rounds here yet.") }
                            } else {
                                item(key = "allTable") { BowStyleTable(data.bowStyles) }
                                item(key = "allNote") {
                                    Muted("Everyone's finished rounds here. Average per animal is out of 24 (predator/prey counts as 2, a fan as its animals).")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun summary(rounds: Int, best: Int?) =
    "$rounds round${if (rounds == 1) "" else "s"}" + (best?.let { " · best $it" } ?: "")

@Composable
private fun RoundRow(r: FriendRoundSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.shootDate.format(UkDateLong), style = MaterialTheme.typography.titleSmall)
                Text("${r.classLine} · ${r.targetCount} targets", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${r.totalPoints}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** Friend's summary here; tap to show their rounds, tap a round for their scorecard. */
@Composable
private fun FriendCard(f: LocationFriend, onOpenRound: (FriendRoundSummary) -> Unit) {
    var expanded by rememberSaveable(f.archerId) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.username, style = MaterialTheme.typography.titleMedium)
                    Text(
                        summary(f.rounds, f.bestTotal) +
                            (f.averagePerAnimal?.let { " · avg ${String.format(Locale.UK, "%.1f", it)} per animal" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide rounds" else "Show rounds")
            }
            if (expanded) {
                f.roundsList.forEach { r ->
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenRound(r) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.shootDate.format(UkDateLong), style = MaterialTheme.typography.bodyMedium)
                            Text(r.classLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${r.totalPoints}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun BowStyleTable(rows: List<BowStyleStats>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Cell("Bow", weight = 1.6f, header = true, start = true)
                Cell("Archers", header = true)
                Cell("Rounds", header = true)
                Cell("Best", header = true)
                Cell("Avg", header = true)
                Cell("Per animal", weight = 1.2f, header = true)
            }
            rows.forEach { b ->
                HorizontalDivider()
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cell(BowStyle.fromCode(b.bowStyle)?.displayName ?: b.bowStyle, weight = 1.6f, start = true)
                    Cell("${b.archers}")
                    Cell("${b.rounds}")
                    Cell(b.bestTotal?.toString() ?: "–")
                    Cell(b.averageTotal?.let { String.format(Locale.UK, "%.0f", it) } ?: "–")
                    Cell(b.averagePerAnimal?.let { String.format(Locale.UK, "%.1f", it) } ?: "–", weight = 1.2f)
                }
            }
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float = 1f, header: Boolean = false, start: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textAlign = if (start) TextAlign.Start else TextAlign.End,
        maxLines = 2,
    )
}
