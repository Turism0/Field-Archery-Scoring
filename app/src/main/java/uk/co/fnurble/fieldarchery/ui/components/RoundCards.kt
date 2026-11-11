package uk.co.fnurble.fieldarchery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.co.fnurble.fieldarchery.data.RoundSummary
import uk.co.fnurble.fieldarchery.data.local.SyncState
import uk.co.fnurble.fieldarchery.domain.RoundStatus
import uk.co.fnurble.fieldarchery.domain.RoundType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A round in a list: date, class line, total; "in progress" badge or upload state. [net] (2.9): the net score with the
 * personal handicap before this round, and that handicap.
 */
@Composable
fun RoundCard(
    r: RoundSummary,
    showSync: Boolean,
    personalBest: Boolean = false,
    net: Pair<Int, Int>? = null,
    /** Average per animal (Woodsman: per target), shown under the points (3.1.08). */
    average: Double? = null,
    onClick: () -> Unit,
) {
    val inProgress = r.status == RoundStatus.IN_PROGRESS
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (inProgress) scheme.secondaryContainer else scheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (inProgress) {
                    Surface(shape = MaterialTheme.shapes.small, color = scheme.primary, contentColor = scheme.onPrimary) {
                        Text(
                            "IN PROGRESS",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.shootDate.format(UkDateLong), style = MaterialTheme.typography.titleMedium)
                    // Your current personal best (3.1.08): the best average for this round type and bow style.
                    if (personalBest) PbBadge()
                }
                Text(
                    "${r.classLine} · ${r.targetsLine}" + if (r.groupSize > 1) " · group of ${r.groupSize}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                r.locationName?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                // Finished rounds upload when signed in; in-progress ones stay on the phone until finished.
                if (showSync && !inProgress) SyncLabel(r.syncState)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${r.totalPoints}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("points", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                average?.let {
                    Text(
                        "avg ${String.format(java.util.Locale.UK, "%.1f", it)}/${if (r.roundType == RoundType.WOODSMAN) "target" else "animal"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                net?.let { (n, h) -> Text("net $n (hcp $h)", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun SyncLabel(state: SyncState) {
    val scheme = MaterialTheme.colorScheme
    val (icon, text, colour) = when (state) {
        SyncState.SYNCED -> Triple(Icons.Filled.Check, "Backed up", scheme.primary)
        SyncState.PENDING -> Triple(Icons.Filled.Refresh, "Waiting to upload", scheme.onSurfaceVariant)
        SyncState.FAILED -> Triple(Icons.Filled.Warning, "Couldn't upload", scheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = colour)
    }
}

/** "Last synced 21:45 · pull down to sync" (time only for today, date and time otherwise). */
@Composable
fun SyncStatusLine(lastSync: Instant?, modifier: Modifier = Modifier) {
    Text(
        syncStatusText(lastSync),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

fun syncStatusText(lastSync: Instant?): String {
    if (lastSync == null) return "Not synced yet · pull down to sync"
    val local = lastSync.atZone(ZoneId.systemDefault())
    val pattern = if (local.toLocalDate() == LocalDate.now()) "HH:mm" else "d MMM HH:mm"
    return "Last synced ${local.format(DateTimeFormatter.ofPattern(pattern))} · pull down to sync"
}

/** "PB": a round that was a new personal best when it was shot. */
@Composable
fun PbBadge(text: String = "PB") {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.small, color = scheme.tertiary, contentColor = scheme.onTertiary) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}
