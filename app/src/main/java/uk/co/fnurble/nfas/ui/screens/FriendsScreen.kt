package uk.co.fnurble.nfas.ui.screens

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.data.FriendRequest
import uk.co.fnurble.nfas.data.FriendSummary
import uk.co.fnurble.nfas.ui.FriendsViewModel
import uk.co.fnurble.nfas.ui.Load
import uk.co.fnurble.nfas.ui.components.SectionCard
import uk.co.fnurble.nfas.ui.components.UkDate
import java.util.Locale

/** Add friends by username, answer requests, and pick a friend to see their rounds. Pull down to refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    vm: FriendsViewModel,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onOpenFriend: (FriendSummary) -> Unit,
) {
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val username by vm.username.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Load when the screen opens, and again after signing in from here.
    LaunchedEffect(signedIn) { if (signedIn) vm.load() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Friends") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!signedIn) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sign in to use Friends", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Friends can see each other's finished scorecards and stats.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onProfile) { Text("Go to Profile") }
            }
            return@Scaffold
        }

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
                item(key = "add") {
                    SectionCard(title = "Add a friend", subtitle = "They'll see your finished rounds once they accept, and you'll see theirs.") {
                        OutlinedTextField(
                            value = username,
                            onValueChange = vm::onUsername,
                            label = { Text("Their username") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { vm.sendRequest() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = vm::sendRequest,
                            enabled = !busy && username.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Send friend request") }
                    }
                }

                when (val o = overview) {
                    Load.Loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    is Load.Failed -> item(key = "failed") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(o.message, color = MaterialTheme.colorScheme.onErrorContainer)
                                FilledTonalButton(onClick = { vm.load() }) { Text("Try again") }
                            }
                        }
                    }
                    is Load.Ready -> {
                        val data = o.value
                        if (data.incoming.isNotEmpty()) {
                            item(key = "incomingHeader") { Header("Friend requests") }
                            items(data.incoming, key = { "in-" + it.archerId }) { req ->
                                RequestRow(req, "wants to be friends", busy) {
                                    TextButton(onClick = { vm.decline(req) }, enabled = !busy) { Text("Decline") }
                                    Button(onClick = { vm.accept(req) }, enabled = !busy) { Text("Accept") }
                                }
                            }
                        }
                        if (data.outgoing.isNotEmpty()) {
                            item(key = "outgoingHeader") { Header("Waiting for them to accept") }
                            items(data.outgoing, key = { "out-" + it.archerId }) { req ->
                                RequestRow(req, "request sent", busy) {
                                    TextButton(onClick = { vm.cancel(req) }, enabled = !busy) { Text("Cancel") }
                                }
                            }
                        }
                        item(key = "friendsHeader") { Header("Friends") }
                        if (data.friends.isEmpty()) {
                            item(key = "noFriends") {
                                Text(
                                    "No friends yet. Add one by username above.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(data.friends, key = { "f-" + it.archerId }) { f -> FriendCard(f) { onOpenFriend(f) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun RequestRow(req: FriendRequest, note: String, busy: Boolean, actions: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(req.username, style = MaterialTheme.typography.titleMedium)
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.padding(8.dp)) else actions()
            }
        }
    }
}

/** Friend with a summary: rounds, best, average per animal, last shot. */
@Composable
private fun FriendCard(f: FriendSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.username, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (f.rounds == 0 && f.woodsmanRounds == 0) "No finished rounds yet"
                    else buildString {
                        if (f.rounds > 0) {
                            append("${f.rounds} round${if (f.rounds == 1) "" else "s"}")
                            f.bestTotal?.let { append(" · best $it") }
                            f.averagePerAnimal?.let { append(" · avg ${String.format(Locale.UK, "%.1f", it)} per animal") }
                        }
                        if (f.woodsmanRounds > 0) {
                            if (isNotEmpty()) append("\n")
                            append("Woodsman: ${f.woodsmanRounds} round${if (f.woodsmanRounds == 1) "" else "s"}")
                            f.woodsmanBest?.let { append(" · best $it") }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                f.lastShootDate?.let {
                    Text("Last shot ${it.format(UkDate)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
