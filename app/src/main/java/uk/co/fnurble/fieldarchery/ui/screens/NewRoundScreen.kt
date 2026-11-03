package uk.co.fnurble.fieldarchery.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedButton
import uk.co.fnurble.fieldarchery.data.local.GroupRole
import uk.co.fnurble.fieldarchery.ui.GroupMemberDraft
import uk.co.fnurble.fieldarchery.ui.components.GroupMemberDialog
import uk.co.fnurble.fieldarchery.ui.components.GroupMemberRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.fieldarchery.data.RoundRepository
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.domain.RoundType
import uk.co.fnurble.fieldarchery.ui.NewRoundViewModel
import uk.co.fnurble.fieldarchery.ui.components.BottomActionBar
import uk.co.fnurble.fieldarchery.ui.components.DateField
import uk.co.fnurble.fieldarchery.ui.components.NumberField
import uk.co.fnurble.fieldarchery.ui.components.OptionDropdown
import uk.co.fnurble.fieldarchery.ui.components.SectionCard
import uk.co.fnurble.fieldarchery.ui.components.SegmentedChoice

/** Quick picks for the number of targets; any number can still be typed. */
private val CommonTargetCounts = listOf(18, 20, 36, 40)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRoundScreen(
    vm: NewRoundViewModel,
    roundType: RoundType,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onStarted: (String) -> Unit,
) {
    val form by vm.form.collectAsStateWithLifecycle()
    // Add / edit a friend or guest in a group round.
    var memberDialog by remember { mutableStateOf<Pair<GroupRole, GroupMemberDraft?>?>(null) }
    // Ask for location when starting (Android only shows the prompt until it's been refused twice).
    // Either answer starts the round; without permission the round simply has no location.
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.start(onStarted)
    }
    val context = LocalContext.current
    fun startRound() {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) vm.start(onStarted)
        else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("New round")
                        Text(roundType.displayName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onEditProfile) { Icon(Icons.Filled.Edit, contentDescription = "Edit profile") }
                },
            )
        },
        bottomBar = {
            BottomActionBar(
                text = if (form?.group == true) "Start group round" else "Start round",
                enabled = form?.isValid == true,
            ) { startRound() }
        },
    ) { padding ->
        val f = form
        if (f == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Who's scoring") {
                SegmentedChoice(listOf(false, true), f.group, { if (it) "Group" else "Just me" }) { g ->
                    vm.update { it.copy(group = g) }
                }
                if (f.group) {
                    Text(
                        "You score everyone on this phone, archer by archer on each target.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(title = "Shoot") {
                DateField(
                    label = "Shoot date",
                    date = f.shootDate,
                    onDate = { d -> if (d != null) vm.update { it.copy(shootDate = d) } },
                )
                val countError = f.targetCountText.isNotEmpty() && f.targetCount == null
                NumberField(
                    label = "Number of targets",
                    value = f.targetCountText,
                    onValue = { v -> vm.update { it.copy(targetCountText = v) } },
                    isError = countError,
                    supportingText = if (countError) "1 to ${RoundRepository.MAX_TARGETS}" else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommonTargetCounts.forEach { n ->
                        SuggestionChip(
                            onClick = { vm.update { it.copy(targetCountText = n.toString()) } },
                            label = { Text("$n") },
                        )
                    }
                }
            }

            SectionCard(
                title = if (f.group) "You" else "Archer",
                subtitle = "Filled in from your profile. Changes here only affect this round.",
            ) {
                SegmentedChoice(ArcherClass.entries, f.archerClass, { it.displayName }) { c ->
                    vm.update { it.copy(archerClass = c) }
                }
                OptionDropdown(
                    label = "Age group",
                    options = AgeGroup.entries,
                    selected = f.ageGroup,
                    optionLabel = { it.displayName },
                    onSelect = { g -> vm.update { it.copy(ageGroupChoice = g) } },
                    supportingText = f.ageGroupSource.replaceFirstChar { it.uppercase() },
                )
                OptionDropdown(
                    label = "Bow style",
                    options = BowStyle.entries,
                    selected = f.bowStyle,
                    optionLabel = { it.label },
                    onSelect = { b -> vm.update { it.copy(bowStyle = b) } },
                    supportingText = if (f.bowStyle != null && f.bowStyle == f.profile.mainBowStyle) "Your main style" else null,
                )
            }

            if (f.group) {
                SectionCard(
                    title = "Group",
                    subtitle = "In scoring order. You're first; up to ${RoundRepository.MAX_GROUP} archers.",
                ) {
                    Text("You", style = MaterialTheme.typography.titleSmall)
                    f.members.forEachIndexed { i, m ->
                        GroupMemberRow(
                            m = m,
                            canUp = i > 0,
                            canDown = i < f.members.lastIndex,
                            onUp = { vm.moveMember(m.key, -1) },
                            onDown = { vm.moveMember(m.key, 1) },
                            onEdit = { memberDialog = m.role to m },
                            onRemove = { vm.removeMember(m.key) },
                        )
                    }
                    val friends = remember(f.members) { vm.friends().filter { fr -> f.members.none { it.archerId == fr.archerId } } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { memberDialog = GroupRole.FRIEND to null },
                            enabled = f.canAddMember && friends.isNotEmpty(),
                        ) { Text("Add friend") }
                        OutlinedButton(
                            onClick = { memberDialog = GroupRole.GUEST to null },
                            enabled = f.canAddMember,
                        ) { Text("Add guest") }
                    }
                    if (friends.isEmpty() && f.canAddMember) {
                        Text(
                            if (!vm.signedIn) "Sign in (Profile) to add friends; guests can be added any time."
                            else "No friends to add. Open Friends once with signal to load your list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!f.isValid) {
                Text(
                    if (f.group && f.members.isEmpty()) "Add at least one friend or guest to the group."
                    else "Fill in the number of targets, class, age group and bow style to start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    memberDialog?.let { (role, existing) ->
        GroupMemberDialog(
            role = role,
            existing = existing,
            friends = vm.friends().filter { fr -> form?.members.orEmpty().none { it.archerId == fr.archerId && it.key != existing?.key } },
            onDismiss = { memberDialog = null },
            onSave = { m ->
                if (existing != null) vm.replaceMember(m) else vm.addMember(m)
                memberDialog = null
            },
        )
    }
}
