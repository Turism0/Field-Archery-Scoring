package uk.co.fnurble.fieldarchery.ui.screens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.fieldarchery.data.AdminArcher
import uk.co.fnurble.fieldarchery.domain.AccountRole
import uk.co.fnurble.fieldarchery.ui.AdminViewModel
import uk.co.fnurble.fieldarchery.ui.Load
import uk.co.fnurble.fieldarchery.ui.components.SectionCard
import uk.co.fnurble.fieldarchery.ui.components.UkDate
import java.time.ZoneId

/**
 * Admin menu: every account and its role. The Owner switches Admin on or off; Admins see the list
 * only. Links to Shoot locations (rename / merge). Pull down to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(vm: AdminViewModel, onBack: () -> Unit, onLocations: () -> Unit, onDuplicates: () -> Unit, onHandicap: () -> Unit = {}) {
    val role by vm.role.collectAsStateWithLifecycle()
    val duplicates by vm.duplicateLocations.collectAsStateWithLifecycle()
    val archers by vm.archers.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val changing by vm.changing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val resetCode by vm.resetCode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val isOwner = role == AccountRole.OWNER
    var confirmReset by remember { mutableStateOf<AdminArcher?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    // Lost admin while on this screen (the server said no): leave.
    LaunchedEffect(role) { if (!role.isAdmin) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin") },
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
                item(key = "duplicates") {
                    Card(
                        onClick = onDuplicates,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (duplicates > 0) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text("Possible duplicates", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (duplicates > 0) "$duplicates to check: merge or mark as different places"
                                    else "Nothing to check",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
                item(key = "locations") {
                    Card(
                        onClick = onLocations,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text("Shoot locations", style = MaterialTheme.typography.titleMedium)
                                Text("Rename, make private or public, merge", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
                item(key = "handicap") {
                    Card(
                        onClick = onHandicap,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, contentDescription = null)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text("Handicap", style = MaterialTheme.typography.titleMedium)
                                Text("NFAS results, targets per day, models", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
                item(key = "intro") {
                    SectionCard(
                        title = "Accounts",
                        subtitle = if (isOwner) "Switch Admin on to let someone rename and merge shoot locations. Only you can make or remove admins."
                        else "Only the owner can make or remove admins.",
                    ) {}
                }
                when (val a = archers) {
                    Load.Loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    is Load.Failed -> item(key = "failed") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(a.message, color = MaterialTheme.colorScheme.onErrorContainer)
                                FilledTonalButton(onClick = { vm.load() }) { Text("Try again") }
                            }
                        }
                    }
                    is Load.Ready -> {
                        item(key = "count") {
                            Text(
                                "${a.value.size} account${if (a.value.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(a.value, key = { it.archerId }) { archer ->
                            ArcherRow(
                                archer = archer,
                                canChange = isOwner && archer.role != AccountRole.OWNER,
                                busy = changing == archer.archerId,
                                anyBusy = changing != null,
                                onSetAdmin = { vm.setAdmin(archer, it) },
                                // Password reset codes (2.4): admins for users, the owner for anyone but themselves.
                                canReset = archer.archerId != vm.myArcherId && archer.role != AccountRole.OWNER &&
                                    (archer.role != AccountRole.ADMIN || isOwner),
                                onReset = { confirmReset = archer },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmReset?.let { archer ->
        AlertDialog(
            onDismissRequest = { confirmReset = null },
            title = { Text("Reset code for ${archer.username}?") },
            text = {
                Text(
                    "Only for someone who has lost both their password and their recovery code. Check it's really " +
                        "${archer.username} first (e.g. in person): the code lets whoever has it set a new password. " +
                        "It works once, for 24 hours.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmReset = null; vm.makeResetCode(archer) }) { Text("Make code") } },
            dismissButton = { TextButton(onClick = { confirmReset = null }) { Text("Cancel") } },
        )
    }
    resetCode?.let { c ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Reset code for ${c.username}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        c.code,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Text(
                        "Give it to ${c.username} (tell them, don't post it). On the sign-in screen they enter their username, " +
                            "tap Forgot your password?, and use this code. Works once" +
                            (c.expiresUtc?.let { ", until " + it.atZone(ZoneId.systemDefault()).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")) } ?: "") +
                            ". Making another replaces it.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = vm::resetCodeShown) { Text("Done") } },
        )
    }
}

@Composable
private fun ArcherRow(
    archer: AdminArcher,
    canChange: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    onSetAdmin: (Boolean) -> Unit,
    canReset: Boolean = false,
    onReset: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(archer.username, style = MaterialTheme.typography.titleMedium)
                    if (archer.role != AccountRole.USER) RoleBadge(archer.role)
                }
                Text(
                    listOfNotNull(
                        archer.createdUtc?.let { "Joined ${it.atZone(ZoneId.systemDefault()).toLocalDate().format(UkDate)}" },
                        archer.lastLoginUtc?.let { "last sign-in ${it.atZone(ZoneId.systemDefault()).toLocalDate().format(UkDate)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                if (canReset) TextButton(onClick = onReset, enabled = !anyBusy) { Text("Password reset code") }
            }
            if (canChange) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(
                            checked = archer.role == AccountRole.ADMIN,
                            onCheckedChange = onSetAdmin,
                            enabled = !anyBusy,
                        )
                        Text("Admin", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleBadge(role: AccountRole) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (role == AccountRole.OWNER) scheme.primary else scheme.tertiaryContainer,
        contentColor = if (role == AccountRole.OWNER) scheme.onPrimary else scheme.onTertiaryContainer,
    ) {
        Text(
            role.displayName.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
