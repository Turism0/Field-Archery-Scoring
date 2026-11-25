package uk.co.fieldarcheryscoring.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fieldarcheryscoring.data.CompareKey
import uk.co.fieldarcheryscoring.domain.Analysis
import uk.co.fieldarcheryscoring.domain.ArrowCounts
import uk.co.fieldarcheryscoring.domain.FormDirection
import uk.co.fieldarcheryscoring.domain.HitCounts
import uk.co.fieldarcheryscoring.domain.Round
import uk.co.fieldarcheryscoring.domain.RoundType
import uk.co.fieldarcheryscoring.domain.Weather
import uk.co.fieldarcheryscoring.ui.Load
import uk.co.fieldarcheryscoring.ui.StatsViewModel
import uk.co.fieldarcheryscoring.ui.components.SectionCard
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Stats → Analysis (3.2.17, Pete): form and consistency, arrows, hits (with a chart over time), target types, start and
 * finish, compared with others, by location and by weather. The same sections as the web app's ui/Analysis.tsx.
 */

private val InnerKillColour = Color(0xFF2F6B3C)
private val KillColour = Color(0xFF7FB88A)
private val WoundColour = Color(0xFFD9A441)
private val MissColour = Color(0xFF9A9A9A)

private fun one(v: Double) = String.format(Locale.UK, "%.1f", v)
private fun pct(n: Int, of: Int) = if (of == 0) "–" else "${(n * 100.0 / of).roundToInt()}%"
private fun per(t: RoundType) = if (t == RoundType.WOODSMAN) "per target" else "per animal"

@Composable
internal fun AnalysisTab(vm: StatsViewModel, onOpenRound: (String) -> Unit) {
    val rounds by vm.shownRounds.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val compare by vm.compare.collectAsStateWithLifecycle()
    val kit by vm.equipment.collectAsStateWithLifecycle()
    val types = filter?.types?.sortedBy { it.ordinal }.orEmpty()

    // Compared with others: one ask for every kind of round shown (round type, bow style, class and age group).
    val groups = remember(rounds) {
        rounds.groupBy { CompareKey.of(it) }.mapNotNull { (k, l) ->
            Analysis.roundAverages(l).map { it.second }.takeIf { it.isNotEmpty() }?.let { k to it.average() }
        }.toMap()
    }
    LaunchedEffect(groups) { if (groups.isNotEmpty()) vm.loadCompare(groups) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        types.forEach { type ->
            val list = rounds.filter { it.type == type }
            if (types.size > 1) item(key = "h-$type") { Text(type.displayName, style = MaterialTheme.typography.titleLarge) }
            if (list.isEmpty()) {
                item(key = "none-$type") { Text("No finished ${type.displayName} rounds match these filters.", style = MaterialTheme.typography.bodyLarge) }
            } else {
                item(key = "form-$type") { FormCard(list, type) }
                item(key = "arrows-$type") { ArrowsCard(list, type) }
                item(key = "hits-$type") { HitsCard(list, type) }
                if (type == RoundType.BIG_GAME) item(key = "types-$type") { TargetTypesCard(list) }
                item(key = "thirds-$type") { ThirdsCard(list, type) }
                item(key = "compare-$type") { CompareCard(list, type, compare, groups) }
                item(key = "locations-$type") { LocationsCard(list, type, onOpenRound) }
                // 3.2.24 (Pete): your bows and arrows compared. 3.2.29: by bow + arrows pair, shown once any round has them
                // (the figures moved here from the Equipment screen).
                if (list.any { Analysis.equipmentPair(it) != null }) {
                    item(key = "equipment-$type") { EquipmentCard(list, type, kit.first, kit.second, onOpenRound) }
                }
                item(key = "weather-$type") { WeatherCard(list, type, weather) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FormCard(rounds: List<Round>, type: RoundType) {
    val c = remember(rounds) { Analysis.consistency(rounds) }
    val f = remember(rounds) { Analysis.form(rounds, type) }
    val s = remember(rounds) { Analysis.streak(rounds) }
    SectionCard(title = "Form and consistency") {
        if (c == null) {
            Text("Finish at least 3 rounds (in these filters) to see how consistent you are.")
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("Average ${per(type)}", one(c.average), Modifier.weight(1f))
            Stat("Usual spread", "±${one(c.spread)}", Modifier.weight(1f))
            Stat("Form", when (f?.direction) { FormDirection.UP -> "▲ Up"; FormDirection.DOWN -> "▼ Down"; FormDirection.STEADY -> "● Steady"; null -> "–" }, Modifier.weight(1f))
        }
        Muted(buildString {
            append("Most of your rounds are within about ${one(c.spread)} of ${one(c.average)} ${per(type)}.")
            f?.let { append(" Your last 5 average ${one(it.recent)}, against ${one(it.before)} before them (${if (it.change >= 0) "+" else ""}${one(it.change)}).") }
            s?.let {
                append(
                    if (it.current > 0) " You're on a run of ${it.current} round${if (it.current == 1) "" else "s"} at or above your average (best run: ${it.best})."
                    else " Your best run at or above your average is ${it.best} round${if (it.best == 1) "" else "s"}.",
                )
            }
        })
    }
}

@Composable
private fun ArrowsCard(rounds: List<Round>, type: RoundType) {
    val a: ArrowCounts = remember(rounds) { Analysis.arrowCounts(rounds) }
    val woodsman = type == RoundType.WOODSMAN
    SectionCard(
        title = if (woodsman) "Which arrow killed" else "Which arrow scored",
        subtitle = if (woodsman) "Targets with the arrows recorded." else "Animals with the arrows recorded (not typed-in scores).",
    ) {
        if (a.total == 0) {
            Text("No rounds with the arrows recorded.")
            return@SectionCard
        }
        val rows = listOf("1st arrow" to a.first, "2nd arrow" to a.second, "3rd arrow" to a.third, (if (woodsman) "No kill" else "Missed") to a.none)
        val max = rows.maxOf { it.second }.coerceAtLeast(1)
        rows.forEachIndexed { i, (label, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Box(
                    Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(n.toFloat() / max)
                            .background(if (i == 3) MissColour else MaterialTheme.colorScheme.primary),
                    )
                }
                Text(pct(n, a.total), Modifier.width(44.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
            }
        }
        Muted("${a.total} ${if (woodsman) "targets" else "animals"}")
    }
}

@Composable
private fun HitsCard(rounds: List<Round>, type: RoundType) {
    val woodsman = type == RoundType.WOODSMAN
    val all = remember(rounds) { Analysis.hitCounts(rounds) }
    val recent = remember(rounds) { Analysis.hitCounts(Analysis.recentRounds(rounds, 10)) }
    val overTime = remember(rounds) { Analysis.hitsOverTime(rounds) }
    SectionCard(
        title = "Hits",
        subtitle = if (woodsman) "Each target: killed, wounded only, or nothing." else "The scoring hit on each animal, or a miss.",
    ) {
        if (rounds.size > 10) HitBar("Last 10 rounds", recent, woodsman)
        HitBar("All ${rounds.size} rounds", all, woodsman)
        HitLegend(woodsman)
        if (overTime.size >= 2) {
            Text("Over time", style = MaterialTheme.typography.titleSmall)
            overTime.forEach { HitBar(it.label, it.hits, woodsman, compact = true) }
        }
    }
}

private fun hitParts(h: HitCounts, woodsman: Boolean): List<Pair<Color, Int>> =
    if (woodsman) listOf(KillColour to h.kill, WoundColour to h.wound, MissColour to h.miss)
    else listOf(InnerKillColour to h.innerKill, KillColour to h.kill, WoundColour to h.wound, MissColour to h.miss)

@Composable
private fun HitBar(label: String, h: HitCounts, woodsman: Boolean, compact: Boolean = false) {
    if (h.total == 0) return
    val parts = hitParts(h, woodsman)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
            Muted(parts.joinToString(" / ") { pct(it.second, h.total) })
        }
        Row(Modifier.fillMaxWidth().height(if (compact) 10.dp else 16.dp).clip(RoundedCornerShape(6.dp))) {
            parts.filter { it.second > 0 }.forEach { (colour, n) ->
                Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(colour))
            }
        }
    }
}

@Composable
private fun HitLegend(woodsman: Boolean) {
    val items = if (woodsman) listOf(KillColour to "Killed", WoundColour to "Wounded only", MissColour to "Nothing")
    else listOf(InnerKillColour to "Inner kill", KillColour to "Kill", WoundColour to "Wound", MissColour to "Miss")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (c, l) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Text(l, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TargetTypesCard(rounds: List<Round>) {
    val types = remember(rounds) { Analysis.byTargetType(rounds) }
    if (types.size < 2) return
    SectionCard(title = "By target type", subtitle = "Average per animal on each kind of target.") {
        types.forEach { t ->
            Row(Modifier.fillMaxWidth()) {
                Text("${t.type.displayName} · ${t.targets} target${if (t.targets == 1) "" else "s"}", Modifier.weight(1f))
                Text(one(t.average), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ThirdsCard(rounds: List<Round>, type: RoundType) {
    val th = remember(rounds) { Analysis.thirds(rounds) }
    SectionCard(title = "Start, middle and finish", subtitle = "Average ${per(type)} in each third of your rounds, in the order you shot them.") {
        Row(Modifier.fillMaxWidth()) {
            Stat("First third", th.start?.let(::one) ?: "–", Modifier.weight(1f))
            Stat("Middle", th.middle?.let(::one) ?: "–", Modifier.weight(1f))
            Stat("Last third", th.finish?.let(::one) ?: "–", Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompareCard(rounds: List<Round>, type: RoundType, compare: Load<List<uk.co.fieldarcheryscoring.data.CompareResult>>?, groups: Map<CompareKey, Double>) {
    val mine = remember(rounds) { rounds.groupBy { CompareKey.of(it) } }
    SectionCard(title = "Compared with others", subtitle = "Your average ${per(type)} against everyone else's rounds of the same kind (no names).") {
        when (compare) {
            null -> Text("Sign in to compare with other archers.")
            Load.Loading -> Muted("Loading…")
            is Load.Failed -> Text(compare.message)
            is Load.Ready -> mine.forEach { (key, list) ->
                val avg = groups[key] ?: return@forEach
                val sample = list.first()
                val who = "${sample.archerClass.displayName} ${sample.ageGroup.displayName} ${sample.bowStyle.displayName}"
                val res = compare.value.firstOrNull { it.key == key }
                val text = res?.let { Analysis.comparedText(it.betterThan, it.rounds, who) } ?: "Not enough rounds from other archers yet"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(who, fontWeight = FontWeight.Bold)
                        Muted("You: ${one(avg)}")
                    }
                    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun LocationsCard(rounds: List<Round>, type: RoundType, onOpenRound: (String) -> Unit) {
    val locs = remember(rounds) { Analysis.byLocation(rounds) }
    SectionCard(title = "By location", subtitle = "Average and best ${per(type)} at each ground.") {
        if (locs.isEmpty()) Text("None of these rounds have a shoot location.")
        locs.forEach { l ->
            Row(Modifier.fillMaxWidth().clickable { onOpenRound(l.bestRound.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, fontWeight = FontWeight.Bold)
                    Muted("${l.rounds} round${if (l.rounds == 1) "" else "s"} · best ${one(l.best)}")
                }
                Text(one(l.average), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 3.2.24 (Pete): each bow and set of arrows: rounds, average and best, and how often you kill (Big Game). */
@Composable
private fun EquipmentCard(
    rounds: List<Round>, type: RoundType, bows: List<uk.co.fieldarcheryscoring.domain.Bow>,
    arrows: List<uk.co.fieldarcheryscoring.domain.ArrowSet>, onOpenRound: (String) -> Unit,
) {
    val pairs = remember(rounds) { Analysis.byEquipment(rounds, Analysis::equipmentPair) }
    SectionCard(title = "By equipment", subtitle = "Average and best ${per(type)} with each bow and set of arrows you've shot together.") {
        EquipmentRows(pairs, onOpenRound) { id -> pairName(id, bows, arrows) }
    }
}

/** "Border Hex 7 + Easton 500s"; "Border Hex 7 (no arrows picked)"; "Easton 500s (no bow picked)". */
private fun pairName(id: String, bows: List<uk.co.fieldarcheryscoring.domain.Bow>, arrows: List<uk.co.fieldarcheryscoring.domain.ArrowSet>): String {
    val bowId = id.substringBefore('|').ifEmpty { null }
    val arrowId = id.substringAfter('|').ifEmpty { null }
    val bow = bowId?.let { b -> bows.firstOrNull { it.id == b }?.let { uk.co.fieldarcheryscoring.domain.Equipment.label(it) } ?: "Unknown bow" }
    val arrow = arrowId?.let { a -> arrows.firstOrNull { it.id == a }?.let { uk.co.fieldarcheryscoring.domain.Equipment.label(it) } ?: "Unknown arrows" }
    return when {
        bow != null && arrow != null -> "$bow + $arrow"
        bow != null -> "$bow (no arrows picked)"
        else -> "$arrow (no bow picked)"
    }
}

@Composable
private fun EquipmentRows(
    list: List<uk.co.fieldarcheryscoring.domain.EquipmentSummary>, onOpenRound: (String) -> Unit, name: (String) -> String,
) {
    list.forEach { e ->
        Row(Modifier.fillMaxWidth().clickable { onOpenRound(e.bestRound.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name(e.id), fontWeight = FontWeight.Bold)
                Muted("${e.rounds} round${if (e.rounds == 1) "" else "s"} · best ${one(e.best)}" +
                    (e.killRate?.let { " · kills ${(it * 100).roundToInt()}%" } ?: ""))
            }
            Text(one(e.average), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WeatherCard(rounds: List<Round>, type: RoundType, weather: Map<String, Weather>) {
    val withWeather = rounds.count { weather[it.id] != null }
    val splits = remember(rounds, weather) { Analysis.weatherSplits(rounds) { weather[it.id] } }
    SectionCard(
        title = "Weather",
        subtitle = "Your average ${per(type)} in different weather. $withWeather of these ${rounds.size} rounds have weather " +
            "(rounds at a location with a map pin). Weather data by Open-Meteo.com.",
    ) {
        if (withWeather < 2) {
            Text("Not enough rounds with weather yet.")
            return@SectionCard
        }
        splits.forEach { sp ->
            Column {
                Text(sp.label, fontWeight = FontWeight.Bold)
                Muted(
                    "${sp.yesLabel}: ${sp.yes.average?.let { "${one(it)} (${sp.yes.rounds})" } ?: "–"} · " +
                        "${sp.noLabel}: ${sp.no.average?.let { "${one(it)} (${sp.no.rounds})" } ?: "–"}",
                )
            }
        }
    }
}
