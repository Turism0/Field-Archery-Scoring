package uk.co.fieldarcheryscoring.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.co.fieldarcheryscoring.data.CachedFriend
import uk.co.fieldarcheryscoring.data.GroupMember
import uk.co.fieldarcheryscoring.data.local.GroupRole
import uk.co.fieldarcheryscoring.data.local.SyncState
import uk.co.fieldarcheryscoring.domain.AgeGroup
import uk.co.fieldarcheryscoring.domain.ArcherClass
import uk.co.fieldarcheryscoring.domain.BowStyle
import uk.co.fieldarcheryscoring.ui.GroupMemberDraft

/** "Friend" / "Guest" label for a group member. */
fun GroupRole.label(): String = when (this) {
    GroupRole.SCORER -> "You"
    GroupRole.FRIEND -> "Friend"
    GroupRole.GUEST -> "Guest"
    GroupRole.MIRROR -> "Own phone"
}

/**
 * The group along the top of the scoring screen: everyone in scoring order with their running total and a tick
 * when they've been scored on this target. The archer being scored is highlighted; tap someone to score them.
 */
@Composable
fun GroupStrip(members: List<GroupMember>, currentRoundId: String, targetNo: Int, onSelect: (GroupMember) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        members.forEach { m ->
            val current = m.roundId == currentRoundId
            val done = m.round.target(targetNo).isComplete
            Surface(
                onClick = { onSelect(m) },
                shape = MaterialTheme.shapes.medium,
                color = if (current) scheme.primaryContainer else scheme.surfaceContainerHigh,
                contentColor = if (current) scheme.onPrimaryContainer else scheme.onSurface,
                border = if (current) BorderStroke(2.dp, scheme.primary) else null,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(m.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (done) Icon(Icons.Filled.Check, contentDescription = "Scored", tint = scheme.primary, modifier = Modifier.size(16.dp))
                    }
                    Text("${m.round.totalPoints}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Group scorecard: everyone's totals side by side, highest first, with what's happening to each round. */
@Composable
fun GroupCard(members: List<GroupMember>, currentRoundId: String, onOpen: (GroupMember) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "Group",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            members.sortedByDescending { it.round.totalPoints }.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (m.roundId == currentRoundId) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            "${m.round.typeAndClass} · ${m.round.targetsComplete}/${m.round.targetCount} targets",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        MemberStatus(m)
                    }
                    Text("${m.round.totalPoints}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (m.roundId != currentRoundId) {
                        TextButton(onClick = { onOpen(m) }) { Text("Open") }
                    } else {
                        Spacer(Modifier.width(72.dp))
                    }
                }
            }
        }
    }
}

/** What happens to a friend's or guest's round. */
@Composable
private fun MemberStatus(m: GroupMember) {
    val scheme = MaterialTheme.colorScheme
    val finished = m.round.status == uk.co.fieldarcheryscoring.domain.RoundStatus.COMPLETED
    val (icon, text, colour) = when (m.role) {
        GroupRole.SCORER -> return
        GroupRole.GUEST -> Triple(null, "Guest · kept on this phone for 30 days", scheme.onSurfaceVariant)
        GroupRole.MIRROR -> Triple(null, "Scoring on their own phone · their round is theirs", scheme.onSurfaceVariant)
        GroupRole.FRIEND -> when {
            !finished -> Triple(null, "Friend · sent to them as a request when the round is finished", scheme.onSurfaceVariant)
            m.syncState == SyncState.SYNCED -> Triple(Icons.Filled.Check, "Sent to ${m.name} to accept", scheme.primary)
            m.syncState == SyncState.FAILED -> Triple(Icons.Filled.Warning, "Couldn't send (still friends?)", scheme.error)
            else -> Triple(Icons.Filled.Refresh, "Waiting to send to ${m.name}", scheme.onSurfaceVariant)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon?.let { Icon(it, contentDescription = null, tint = colour, modifier = Modifier.size(14.dp)) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = colour)
    }
}

/** A friend or guest on the new round screen, with move up / down, edit and remove. */
@Composable
fun GroupMemberRow(
    m: GroupMemberDraft,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${m.role.label()} · ${m.archerClass.displayName} ${m.ageGroup.displayName} ${m.bowStyle.code}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
        IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${m.name}") }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${m.name}") }
    }
}

/**
 * Add or edit a friend or guest: a friend is picked from your friends list, a guest is a name. Each has their own
 * class, age group and bow style.
 */
@Composable
fun GroupMemberDialog(
    role: GroupRole,
    existing: GroupMemberDraft?,
    friends: List<CachedFriend>,
    onDismiss: () -> Unit,
    onSave: (GroupMemberDraft) -> Unit,
) {
    var friend by remember { mutableStateOf(existing?.archerId?.let { id -> friends.firstOrNull { it.archerId == id } }) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var cls by remember { mutableStateOf(existing?.archerClass) }
    var age by remember { mutableStateOf(existing?.ageGroup ?: AgeGroup.ADULT) }
    var bow by remember { mutableStateOf(existing?.bowStyle) }
    val isFriend = role == GroupRole.FRIEND
    val who = if (isFriend) friend?.username ?: existing?.name else name.trim().takeIf { it.isNotEmpty() && it.length <= 40 }
    val ok = who != null && cls != null && bow != null && (!isFriend || existing != null || friend != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit ${existing.name}" else if (isFriend) "Add a friend" else "Add a guest") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isFriend) {
                    if (existing != null) {
                        Text(existing.name, style = MaterialTheme.typography.titleMedium)
                    } else {
                        OptionDropdown(
                            label = "Friend",
                            options = friends,
                            selected = friend,
                            optionLabel = { it.username },
                            onSelect = { friend = it },
                            supportingText = "Their round is sent to them to accept when you finish.",
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Guest's name") },
                        singleLine = true,
                        supportingText = { Text("Kept on this phone only, for 30 days.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SegmentedChoice(ArcherClass.entries, cls, { it.displayName }) { cls = it }
                OptionDropdown(
                    label = "Age group",
                    options = AgeGroup.entries,
                    selected = age,
                    optionLabel = { it.displayName },
                    onSelect = { g -> if (g != null) age = g },
                )
                OptionDropdown(
                    label = "Bow style",
                    options = BowStyle.entries,
                    selected = bow,
                    optionLabel = { it.label },
                    onSelect = { bow = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ok,
                onClick = {
                    val base = existing ?: GroupMemberDraft(role = role, name = who!!, archerClass = cls!!, ageGroup = age, bowStyle = bow!!)
                    onSave(
                        base.copy(
                            name = who!!,
                            archerId = if (isFriend) friend?.archerId ?: existing?.archerId else null,
                            archerClass = cls!!,
                            ageGroup = age,
                            bowStyle = bow!!,
                        ),
                    )
                },
            ) { Text(if (existing != null) "Save" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
