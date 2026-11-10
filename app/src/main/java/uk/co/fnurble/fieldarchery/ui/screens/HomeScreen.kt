package uk.co.fnurble.fieldarchery.ui.screens

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import uk.co.fnurble.fieldarchery.domain.HomeLayout
import uk.co.fnurble.fieldarchery.domain.HomeTileId
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import uk.co.fnurble.fieldarchery.data.ServerState
import uk.co.fnurble.fieldarchery.domain.AccountRole
import uk.co.fnurble.fieldarchery.ui.HomeViewModel
import uk.co.fnurble.fieldarchery.ui.components.syncStatusText

/**
 * Home: account / sync status and tiles – Score a round, Score history, Stats, Handicaps, Friends, Shoot
 * locations – plus an Admin tile for Admins and the Owner. Pull down to sync now. Arrange tiles (2.4.01): the order and
 * which show are the archer's own, kept on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onProfile: () -> Unit,
    onScoreRound: () -> Unit,
    onHistory: () -> Unit,
    onStats: () -> Unit,
    onFriends: () -> Unit,
    onHandicap: () -> Unit,
    onAdmin: () -> Unit,
    onMap: () -> Unit,
) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val handicaps by vm.handicaps.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val username by vm.username.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val role by vm.role.collectAsStateWithLifecycle()
    val duplicates by vm.duplicateLocations.collectAsStateWithLifecycle()
    val roundOffers by vm.roundOffers.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()

    // Check the server when Home opens or the device comes back online; while it's down, every 30 s.
    LaunchedEffect(online) { if (online) vm.checkServer() }
    LaunchedEffect(online, server) {
        while (online && server == ServerState.UNAVAILABLE) {
            delay(30_000)
            vm.checkServer()
        }
    }
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
                title = { Text("Field Archery Scoring") },
                actions = {
                    if (editing != null) TextButton(onClick = vm::doneArranging) { Text("Done") }
                    else IconButton(onClick = onProfile) { Icon(Icons.Filled.Person, contentDescription = "Profile") }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionNotice(online, server)
                AccountStrip(signedIn, username, role, syncStatusText(lastSync), onProfile)

                val s = summary
                // Admin only for admins; hidden tiles left out (except while arranging, 2.4.01).
                val shown = HomeTileId.entries.filter { it != HomeTileId.ADMIN || (signedIn && role.isAdmin) }
                val arranging = editing
                if (arranging != null) {
                    ArrangeTiles(
                        layout = arranging,
                        shown = shown,
                        onMove = { id, by -> vm.moveTile(id, by, shown) },
                        onHide = vm::setTileHidden,
                        onReset = vm::resetTiles,
                    )
                    return@Column
                }
                for (id in layout.order.filter { it in shown && it !in layout.hidden }) {
                    key(id) {
                        when (id) {
                            HomeTileId.SCORE -> HomeTile(
                                icon = Icons.Filled.Add,
                                title = "Score a round",
                                subtitle = when {
                                    s == null -> " "
                                    s.inProgress == 1 -> "1 round in progress · continue or start new"
                                    s.inProgress > 1 -> "${s.inProgress} rounds in progress · continue or start new"
                                    else -> "Start a new round"
                                },
                                highlighted = true,
                                onClick = onScoreRound,
                            )
                            HomeTileId.HISTORY -> HomeTile(
                                icon = Icons.AutoMirrored.Filled.List,
                                title = "Score history",
                                subtitle = when {
                                    s == null -> " "
                                    s.finished == 0 -> "No finished rounds yet"
                                    else -> "${s.finished} finished round${if (s.finished == 1) "" else "s"}" +
                                        (s.best?.let { " · best $it" } ?: "")
                                },
                                onClick = onHistory,
                            )
                            HomeTileId.STATS -> HomeTile(
                                icon = Icons.Filled.Star,
                                title = "Stats",
                                subtitle = when {
                                    s?.finished == 0 -> "Personal bests, trends and seasons once you finish a round"
                                    else -> "Personal bests, trends and season summaries"
                                },
                                onClick = onStats,
                            )
                            HomeTileId.HANDICAP -> HomeTile(
                                icon = Icons.Filled.Info,
                                title = "Handicaps",
                                // 2.9 (Pete: the old wording didn't make sense).
                                subtitle = if (handicaps.isEmpty()) "Your personal handicap, and the class multipliers for handicap competitions"
                                else "Your handicap: " + handicaps.joinToString(" · ") { "${it.bowStyle.code} ${it.handicap}" } + " · class multipliers",
                                onClick = onHandicap,
                            )
                            HomeTileId.FRIENDS -> HomeTile(
                                icon = Icons.Filled.Face,
                                title = "Friends",
                                subtitle = when {
                                    !signedIn -> "Sign in to see friends' scorecards"
                                    roundOffers > 0 -> "$roundOffers round${if (roundOffers == 1) "" else "s"} scored for you to accept"
                                    else -> "Friends' scorecards and stats, requests"
                                },
                                onClick = onFriends,
                                badge = if (signedIn) roundOffers else 0,
                            )
                            HomeTileId.LOCATIONS -> HomeTile(
                                icon = Icons.Filled.LocationOn,
                                title = "Shoot locations",
                                subtitle = if (signedIn) "Map of locations, your scorecards and stats at each" else "Sign in to see the map of shoot locations",
                                onClick = onMap,
                            )
                            HomeTileId.ADMIN -> HomeTile(
                                icon = Icons.Filled.Settings,
                                title = "Admin",
                                subtitle = when {
                                    duplicates > 0 -> "$duplicates possible duplicate location${if (duplicates == 1) "" else "s"} to check"
                                    role == AccountRole.OWNER -> "Accounts, admins and shoot locations"
                                    else -> "Accounts and shoot locations"
                                },
                                onClick = onAdmin,
                                badge = duplicates,
                            )
                        }
                    }
                }
                TextButton(onClick = vm::startArranging, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Arrange tiles")
                }
            }
        }
    }
}

/**
 * Arranging Home (2.4.01, Pete): drag a tile by its handle, or use the arrows; switch tiles off to hide them (Score a
 * round always shows). Done (top bar) saves.
 */
@Composable
private fun ArrangeTiles(
    layout: HomeLayout,
    shown: List<HomeTileId>,
    onMove: (HomeTileId, Int) -> Unit,
    onHide: (HomeTileId, Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val ids = layout.order.filter { it in shown }
    val currentIds by rememberUpdatedState(ids)
    val move by rememberUpdatedState(onMove)
    var dragging by remember { mutableStateOf<HomeTileId?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<HomeTileId, Int>() }
    val gap = with(LocalDensity.current) { 8.dp.toPx() }

    Text(
        "Drag a tile by ≡ or use the arrows. Switch a tile off to hide it. Tap Done at the top when you're finished.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.forEachIndexed { index, id ->
            key(id) {
                val isDragging = dragging == id
                val hidden = id in layout.hidden
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .onSizeChanged { heights[id] = it.height },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "Drag to move ${id.title}",
                            modifier = Modifier
                                .padding(8.dp)
                                .pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = { dragging = id; dragOffset = 0f },
                                        onDragEnd = { dragging = null; dragOffset = 0f },
                                        onDragCancel = { dragging = null; dragOffset = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val list = currentIds
                                        val i = list.indexOf(id)
                                        // Past half of the next tile: swap with it, and carry on from its place.
                                        if (dragOffset > 0 && i < list.lastIndex) {
                                            val h = (heights[list[i + 1]] ?: 0) + gap
                                            if (dragOffset > h / 2) { move(id, 1); dragOffset -= h }
                                        } else if (dragOffset < 0 && i > 0) {
                                            val h = (heights[list[i - 1]] ?: 0) + gap
                                            if (-dragOffset > h / 2) { move(id, -1); dragOffset += h }
                                        }
                                    }
                                },
                        )
                        Text(
                            id.title + if (hidden) " (hidden)" else "",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onMove(id, -1) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${id.title} up")
                        }
                        IconButton(onClick = { onMove(id, 1) }, enabled = index < ids.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${id.title} down")
                        }
                        Switch(checked = !hidden, onCheckedChange = { on -> onHide(id, !on) }, enabled = id.canHide)
                    }
                }
            }
        }
    }
    if (!layout.isDefault) {
        TextButton(onClick = onReset) { Text("Back to the usual order") }
    }
}

/** Small notice when this device is offline, or it's online but the server isn't answering. Nothing otherwise. */
@Composable
private fun ConnectionNotice(online: Boolean, server: ServerState) {
    val scheme = MaterialTheme.colorScheme
    val (title, text, colours) = when {
        !online -> Triple(
            "Device offline",
            "Scoring works as normal. Rounds back up when you're online again.",
            scheme.surfaceContainerHighest to scheme.onSurface,
        )
        server == ServerState.UNAVAILABLE -> Triple(
            "Server unavailable",
            "Scoring works as normal. Rounds back up when the server is back.",
            scheme.errorContainer to scheme.onErrorContainer,
        )
        else -> return
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colours.first,
        contentColor = colours.second,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, Modifier.size(20.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** "Signed in as X · Last synced 21:45 · pull down to sync", or a prompt to sign in. */
@Composable
private fun AccountStrip(signedIn: Boolean, username: String?, role: AccountRole, syncText: String, onProfile: () -> Unit) {
    Surface(
        onClick = onProfile,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (signedIn) {
                Text(
                    "Signed in as $username" + if (role.isAdmin) " · ${role.displayName}" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(syncText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Not signed in", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Scoring works offline. Sign in (Profile) to back up your rounds and use Friends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    /** Small count on the tile (e.g. items waiting for an Admin); hidden when 0. */
    badge: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (highlighted) scheme.primaryContainer else scheme.surfaceContainer
    val content = if (highlighted) scheme.onPrimaryContainer else scheme.onSurface
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f),
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container,
            disabledContentColor = content,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (highlighted) scheme.primary else scheme.secondaryContainer,
                contentColor = if (highlighted) scheme.onPrimary else scheme.onSecondaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.75f))
            }
            if (badge > 0) {
                Badge(containerColor = scheme.error, contentColor = scheme.onError) {
                    Text("$badge", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
