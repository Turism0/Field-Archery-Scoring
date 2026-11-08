package uk.co.fnurble.fieldarchery.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.fieldarchery.domain.BestAverage
import uk.co.fnurble.fieldarchery.domain.BigGameScoring
import uk.co.fnurble.fieldarchery.domain.PersonalBest
import uk.co.fnurble.fieldarchery.domain.PersonalStats
import uk.co.fnurble.fieldarchery.domain.Round
import uk.co.fnurble.fieldarchery.domain.RoundType
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.ui.StatsViewModel
import uk.co.fnurble.fieldarchery.ui.components.FilterMenuChip
import uk.co.fnurble.fieldarchery.ui.components.SectionCard
import uk.co.fnurble.fieldarchery.ui.components.SegmentedChoice
import uk.co.fnurble.fieldarchery.ui.components.UkDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Stats from your own finished rounds: personal bests, trends over time and season (calendar year) summaries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: StatsViewModel, onBack: () -> Unit, onOpenRound: (roundId: String) -> Unit) {
    val rounds by vm.rounds.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Bests", "Trends", "Seasons")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Stats") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
            }
        },
    ) { padding ->
        val list = rounds
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    "Finish a round to see your stats.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                tab == 0 -> BestsTab(vm, onOpenRound)
                tab == 1 -> TrendsTab(vm, onOpenRound)
                else -> SeasonsTab(vm, onOpenRound)
            }
        }
    }
}

// ---------------- Bests ----------------

@Composable
private fun BestsTab(vm: StatsViewModel, onOpenRound: (String) -> Unit) {
    val bests by vm.bests.collectAsStateWithLifecycle()
    val averages by vm.bestAverages.collectAsStateWithLifecycle()
    // One card per round type and bow style.
    val groups = bests.groupBy { it.key.type to it.key.bowStyle }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "about") {
            Text(
                "Best totals for each round type, bow style and number of targets, from fully scored rounds. " +
                    "Best average counts rounds of ${PersonalStats.MIN_TARGETS_FOR_BEST_AVERAGE}+ targets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (groups.isEmpty()) item(key = "none") {
            Text("No fully scored rounds yet.", style = MaterialTheme.typography.bodyLarge)
        }
        groups.forEach { (k, list) ->
            item(key = "${k.first}-${k.second}") {
                BestsCard(k.first, k.second, list, averages.firstOrNull { it.type == k.first && it.bowStyle == k.second }, onOpenRound)
            }
        }
    }
}

@Composable
private fun BestsCard(type: RoundType, bow: BowStyle, list: List<PersonalBest>, average: BestAverage?, onOpenRound: (String) -> Unit) {
    SectionCard(title = "${type.displayName} · ${bow.label}") {
        average?.let { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenRound(a.round.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Best average ${perWhat(type)}", style = MaterialTheme.typography.titleSmall)
                    Text(roundLine(a.round), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(one(a.average), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider()
        }
        list.forEach { pb ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenRound(pb.best.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${pb.key.targetCount} targets", style = MaterialTheme.typography.titleSmall)
                    Text(roundLine(pb.best), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${pb.roundsShot} round${if (pb.roundsShot == 1) "" else "s"} · average ${pb.averageTotal.roundToInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${pb.best.totalPoints}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------- Trends ----------------

@Composable
private fun TrendsTab(vm: StatsViewModel, onOpenRound: (String) -> Unit) {
    val rounds by vm.rounds.collectAsStateWithLifecycle()
    val type by vm.trendType.collectAsStateWithLifecycle()
    val bow by vm.trendBow.collectAsStateWithLifecycle()
    val points by vm.trend.collectAsStateWithLifecycle()
    val all = rounds.orEmpty()
    val types = all.map { it.type }.distinct().sortedBy { it.ordinal }
    val bows = all.filter { it.type == type }.map { it.bowStyle }.distinct().sortedBy { it.ordinal }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "choose") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (types.size > 1) SegmentedChoice(types, type, { it.displayName }) { vm.chooseTrendType(it) }
                FilterMenuChip("Bow", "All bows", bows, bow, { it.label }, { vm.chooseTrendBow(it) })
            }
        }
        item(key = "chart") {
            SectionCard(title = "Average ${perWhat(type)}", subtitle = "Each dot is a round; the line is the average of the last 5.") {
                if (points.size < 2) {
                    Text("Shoot at least two rounds of this kind to see a trend.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    TrendChart(points, max = if (type == RoundType.WOODSMAN) 30.0 else BigGameScoring.MAX_PER_ANIMAL.toDouble())
                }
            }
        }
        if (points.size >= 2) item(key = "summary") { TrendSummary(points) }
        if (points.isNotEmpty()) {
            item(key = "recentHeader") {
                Text("Rounds (newest first)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            items(points.reversed(), key = { it.round.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenRound(p.round.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.date.format(UkDate), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${p.round.targetCount} targets · ${p.round.totalPoints} points" +
                                (if (bow == null) " · ${p.round.bowStyle.code}" else "") +
                                (p.round.location.name?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(one(p.average), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Last 5 against the 5 before, and the overall average. */
@Composable
private fun TrendSummary(points: List<PersonalStats.TrendPoint>) {
    val last = points.takeLast(5).map { it.average }.average()
    val before = points.dropLast(5).takeLast(5).map { it.average }
    val overall = points.map { it.average }.average()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            SummaryStat("Last ${minOf(5, points.size)}", one(last), Modifier.weight(1f))
            if (before.isNotEmpty()) {
                val change = last - before.average()
                SummaryStat("vs the ${before.size} before", (if (change >= 0) "+" else "") + one(change), Modifier.weight(1f))
            }
            SummaryStat("All ${points.size}", one(overall), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Dots for each round's average (spaced by date) and a line for the rolling average. Drawn here, no chart library. */
@Composable
private fun TrendChart(points: List<PersonalStats.TrendPoint>, max: Double) {
    val values = points.flatMap { listOf(it.average, it.rolling) }
    // Whole-number range around the data, so small changes show; never above the maximum possible.
    var lo = floor(values.min()) - 1
    var hi = ceil(values.max()) + 1
    lo = lo.coerceAtLeast(0.0)
    hi = hi.coerceAtMost(max)
    if (hi - lo < 2) { lo = (lo - 1).coerceAtLeast(0.0); hi = (hi + 1).coerceAtMost(max) }
    val first = points.first().date
    val days = ChronoUnit.DAYS.between(first, points.last().date).coerceAtLeast(1)
    val dot = MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant

    Column {
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            Column(Modifier.width(32.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(hi.roundToInt().toString(), style = MaterialTheme.typography.labelSmall)
                Text(((hi + lo) / 2).let { if (it % 1.0 == 0.0) it.roundToInt().toString() else one(it) }, style = MaterialTheme.typography.labelSmall)
                Text(lo.roundToInt().toString(), style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
                fun x(i: Int) = if (points.size == 1) size.width / 2
                else (ChronoUnit.DAYS.between(first, points[i].date).toFloat() / days) * size.width
                fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
                // Grid: top, middle, bottom.
                listOf(0f, size.height / 2, size.height).forEach { gy ->
                    drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                points.forEachIndexed { i, p -> drawCircle(dot, radius = 3.5.dp.toPx(), center = Offset(x(i), y(p.average))) }
                val path = Path().apply {
                    points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.rolling)) else lineTo(x(i), y(p.rolling)) }
                }
                drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(first.format(UkDate), style = MaterialTheme.typography.labelSmall)
            Text(points.last().date.format(UkDate), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(10.dp)) { drawCircle(dot) }
            Text("Round", style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
                drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.5.dp.toPx())
            }
            Text("Average of last 5", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------- Seasons ----------------

@Composable
private fun SeasonsTab(vm: StatsViewModel, onOpenRound: (String) -> Unit) {
    val seasons by vm.seasons.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(seasons, key = { "${it.year}-${it.type}" }) { s -> SeasonCard(s, onOpenRound) }
    }
}

@Composable
private fun SeasonCard(s: PersonalStats.Season, onOpenRound: (String) -> Unit) {
    SectionCard(
        title = "${s.year} · ${s.type.displayName}",
        subtitle = "${s.rounds} round${if (s.rounds == 1) "" else "s"} · ${s.targets} targets" +
            (if (s.locations > 0) " · ${s.locations} location${if (s.locations == 1) "" else "s"}" else "") +
            (if (s.personalBests > 0) " · ${s.personalBests} PB${if (s.personalBests == 1) "" else "s"}" else ""),
    ) {
        Row(Modifier.fillMaxWidth()) {
            SummaryStat("Average ${perWhat(s.type)}", s.stats.averagePerAnimal?.let { one(it) } ?: "–", Modifier.weight(1f))
            if (s.type == RoundType.BIG_GAME) SummaryStat("Inner kills", "${s.stats.innerKills}", Modifier.weight(1f))
            SummaryStat("Kills", "${s.stats.kills}", Modifier.weight(1f))
        }
        MoreStats(s.stats)
        s.bestRound?.let { b ->
            Row(Modifier.fillMaxWidth().clickable { onOpenRound(b.id) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Best round", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${b.targetCount} targets · ${b.bowStyle.code} · " + roundLine(b),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${b.totalPoints}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        if (s.byBow.size > 1) {
            HorizontalDivider()
            s.byBow.forEach { (bow, count, avg) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(bow.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "$count round${if (count == 1) "" else "s"} · avg ${avg?.let { one(it) } ?: "–"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------- Helpers ----------------

private fun perWhat(type: RoundType) = if (type == RoundType.WOODSMAN) "per target" else "per animal"

private fun one(v: Double) = String.format(Locale.UK, "%.1f", v)

/** "3 May 2026 · Sherwood Archers". */
private fun roundLine(r: Round) = r.shootDate.format(UkDate) + (r.location.name?.let { " · $it" } ?: "")
