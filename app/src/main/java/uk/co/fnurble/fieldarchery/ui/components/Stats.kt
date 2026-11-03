package uk.co.fnurble.fieldarchery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.fnurble.fieldarchery.domain.BigGameScoring
import uk.co.fnurble.fieldarchery.domain.RoundStats
import uk.co.fnurble.fieldarchery.domain.ordinal
import java.util.Locale

/**
 * Running totals for a round: total, average, progress, hit counts and which arrow scored.
 * [compact] drops the per-arrow row to save space on the scoring screen.
 */
@Composable
fun RunningStats(stats: RoundStats, modifier: Modifier = Modifier, compact: Boolean = false) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                BigStat("Total", "${stats.totalPoints}")
                BigStat(if (stats.maxPerAnimal == BigGameScoring.MAX_PER_ANIMAL) "Avg per animal" else "Avg per target", stats.averagePerAnimal?.let { String.format(Locale.UK, "%.1f", it) } ?: "–")
                BigStat("Scored", "${stats.targetsScored}/${stats.targetCount}")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
            Row(Modifier.fillMaxWidth()) {
                // Woodsman has no inner kill; its counts are arrows.
                if (stats.maxPerAnimal == BigGameScoring.MAX_PER_ANIMAL) SmallStat("Inner kill", stats.innerKills)
                SmallStat("Kill", stats.kills)
                SmallStat("Wound", stats.wounds)
                SmallStat("Miss", stats.misses)
            }
            if (!compact) Row(Modifier.fillMaxWidth()) {
                stats.byArrow.forEach { (arrow, count) ->
                    SmallStat(if (stats.maxPerAnimal == BigGameScoring.MAX_PER_ANIMAL) "${ordinal(arrow)} arrow" else "Kill ${ordinal(arrow)}", count)
                }
                SmallStat("Overrides", stats.overrides, dimWhenZero = true)
            }
        }
    }
}

@Composable
private fun RowScope.BigStat(label: String, value: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RowScope.SmallStat(label: String, value: Int, dimWhenZero: Boolean = false) {
    val colour = if (dimWhenZero && value == 0) fadedContent else Color.Unspecified
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colour)
        Text(label, style = MaterialTheme.typography.labelSmall, color = colour)
    }
}

/** Faded content colour for zero values that don't matter much. */
private val fadedContent: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
