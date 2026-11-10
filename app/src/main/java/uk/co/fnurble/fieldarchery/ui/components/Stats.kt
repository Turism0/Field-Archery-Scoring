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
import uk.co.fnurble.fieldarchery.domain.HandicapModel
import uk.co.fnurble.fieldarchery.domain.PersonalHandicap
import uk.co.fnurble.fieldarchery.domain.RoundType
import uk.co.fnurble.fieldarchery.ui.OwnHandicap
import uk.co.fnurble.fieldarchery.domain.Round
import uk.co.fnurble.fieldarchery.domain.RoundStatus
import uk.co.fnurble.fieldarchery.domain.RoundStats
import uk.co.fnurble.fieldarchery.domain.ordinal
import java.util.Locale

/**
 * Running totals for a round: total, average, progress, hit counts and which arrow scored.
 * [compact] drops the per-arrow row to save space on the scoring screen.
 * [showTotal] false on scorecards (2.7.06), whose header already shows the total in large type.
 * [oneLine] (2.9.03): short screens while scoring – just "Total · avg · scored" on one line.
 */
@Composable
fun RunningStats(stats: RoundStats, modifier: Modifier = Modifier, compact: Boolean = false, showTotal: Boolean = true, oneLine: Boolean = false) {
    if (oneLine) {
        val avg = stats.averagePerAnimal?.let { String.format(Locale.UK, "%.1f", it) } ?: "–"
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total ${stats.totalPoints}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Avg $avg", style = MaterialTheme.typography.titleMedium)
                Text("${stats.targetsScored}/${stats.targetCount} scored", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                if (showTotal) BigStat("Total", "${stats.totalPoints}")
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

/**
 * Handicap competition scores for a finished Big Game round (2.9): the net score with your personal handicap before
 * this round ([own]: only for your own rounds; its handicap null when you didn't have one yet), and score × the
 * multiplier for the bow style and class ([model]; nothing before it's downloaded from a 2.9 server).
 */
@Composable
fun HandicapCard(round: Round, model: HandicapModel?, own: OwnHandicap? = null, modifier: Modifier = Modifier) {
    if (round.type != RoundType.BIG_GAME || round.status != RoundStatus.COMPLETED) return
    val total = round.totalPoints
    val mult = model?.multiplier(HandicapModel.Archer(round.bowStyle, round.archerClass, round.ageGroup))
    if (own == null && mult == null) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Handicap competition", style = MaterialTheme.typography.titleMedium)
            if (own != null) {
                val h = own.handicap
                if (h == null) {
                    Text(
                        "No ${round.bowStyle.displayName} handicap before this round: it needs 3 finished, fully scored rounds with that bow.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val net = PersonalHandicap.net(total, round.targetCount, h.handicap)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Net score", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "$total + ${net - total} (${round.bowStyle.displayName} handicap ${h.handicap} before this round" +
                                    (if (round.targetCount != PersonalHandicap.PAR_TARGETS) ", scaled to ${round.targetCount} targets" else "") +
                                    "). Par ${PersonalHandicap.parFor(round.targetCount)}.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("$net", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (mult != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("With the class multiplier", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("$total × ${"%.2f".format(java.util.Locale.UK, mult)} (${round.classLine})", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${HandicapModel.applyMultiplier(total, mult)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
