package uk.co.fieldarcheryscoring.ui.screens

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
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import uk.co.fieldarcheryscoring.data.LiveMethod
import uk.co.fieldarcheryscoring.data.local.GroupRole
import uk.co.fieldarcheryscoring.ui.GroupMemberDraft
import uk.co.fieldarcheryscoring.ui.components.GroupMemberDialog
import uk.co.fieldarcheryscoring.ui.components.GroupMemberRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fieldarcheryscoring.data.RoundRepository
import uk.co.fieldarcheryscoring.domain.AgeGroup
import uk.co.fieldarcheryscoring.domain.ArcherClass
import uk.co.fieldarcheryscoring.domain.BowStyle
import uk.co.fieldarcheryscoring.domain.RoundType
import uk.co.fieldarcheryscoring.ui.NewRoundViewModel
import uk.co.fieldarcheryscoring.ui.components.BottomActionBar
import uk.co.fieldarcheryscoring.ui.components.DateField
import uk.co.fieldarcheryscoring.ui.components.NumberField
import uk.co.fieldarcheryscoring.ui.components.OptionDropdown
import uk.co.fieldarcheryscoring.ui.components.SectionCard
import uk.co.fieldarcheryscoring.ui.components.SegmentedChoice

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
    /** Live group made: open its lobby. */
    onLive: (groupId: String) -> Unit = {},
) {
    val form by vm.form.collectAsStateWithLifecycle()
    // Add / edit a friend or guest in a group round.
    var memberDialog by remember { mutableStateOf<Pair<GroupRole, GroupMemberDraft?>?>(null) }
    // Ask for location when starting (Android only shows the prompt until it's been refused twice).
    // Either answer starts the round; without permission the round simply has no location.
    fun go() { if (form?.live == true) vm.startLive(onLive) else vm.start(onStarted) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { go() }
    val context = LocalContext.current
    fun startRound() {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) go()
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
                text = when {
                    form?.live == true -> if (form?.starting == true) "Creating…" else "Create live group"
                    form?.group == true -> "Start group round"
                    else -> "Start round"
                },
                enabled = form?.isValid == true && form?.starting != true,
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
                val mode = when { f.live -> 2; f.group -> 1; else -> 0 }
                SegmentedChoice(listOf(0, 1, 2), mode, { when (it) { 1 -> "Group"; 2 -> "Live group"; else -> "Just me" } }) { m ->
                    vm.update { it.copy(group = m > 0, live = m == 2, error = null) }
                }
                when (mode) {
                    1 -> Text(
                        "You score everyone on this phone, archer by archer on each target.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    2 -> {
                        Text(
                            "Others join on their own phones with a code or QR. Needs signing in; with no signal, phones " +
                                "share scores directly (Bluetooth).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!vm.signedIn) {
                            Text("Sign in (Profile) to make a live group.", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        LiveMethod.entries.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = f.liveMethod == m, onClick = { vm.update { it.copy(liveMethod = m) } }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = f.liveMethod == m, onClick = { vm.update { it.copy(liveMethod = m) } })
                                Column {
                                    Text(m.displayName, style = MaterialTheme.typography.bodyLarge)
                                    Text(m.description, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        val expectedError = f.liveExpectedText.isNotEmpty() && f.liveExpected == null
                        NumberField(
                            label = "Archers in the group (with you)",
                            value = f.liveExpectedText,
                            onValue = { v -> vm.update { it.copy(liveExpectedText = v) } },
                            isError = expectedError,
                            supportingText = if (expectedError) "${f.liveExpectedRange.first} to ${f.liveExpectedRange.last}"
                            else "Start waits until this many are in (friends and guests you add count).",
                        )
                        if (f.liveMethod.checked) {
                            Text(
                                "Go through the group archer by archer: they call their score and both their scorers enter it. " +
                                    "Everyone moves on once the two match.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                f.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
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
                val startError = f.startTargetError
                NumberField(
                    label = "Starting target (optional)",
                    value = f.startTargetText,
                    onValue = { v -> vm.update { it.copy(startTargetText = v) } },
                    isError = startError,
                    supportingText = if (startError) "1 to ${RoundRepository.MAX_START_TARGET}"
                    else "The course's number on the first target you shoot, if not 1 (e.g. 19 for the second half of a 36). Just noted on the scorecard: scoring starts at 1.",
                )
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
                    subtitle = if (f.live) "Friends and guests you add are scored on your phone (optional: others can join with the code)."
                    else "In scoring order. You're first; up to ${RoundRepository.MAX_GROUP} archers.",
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
                    if (f.group && !f.live && f.members.isEmpty()) "Add at least one friend or guest to the group."
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
