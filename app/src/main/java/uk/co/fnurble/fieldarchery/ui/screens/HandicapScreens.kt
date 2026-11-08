package uk.co.fnurble.fieldarchery.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.fieldarchery.data.HandicapEvent
import uk.co.fnurble.fieldarchery.domain.AccountRole
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.domain.HandicapModel
import uk.co.fnurble.fieldarchery.ui.AdminHandicapViewModel
import uk.co.fnurble.fieldarchery.ui.HandicapCalculatorViewModel
import uk.co.fnurble.fieldarchery.ui.Load
import uk.co.fnurble.fieldarchery.ui.components.NumberField
import uk.co.fnurble.fieldarchery.ui.components.OptionDropdown
import uk.co.fnurble.fieldarchery.ui.components.SectionCard
import uk.co.fnurble.fieldarchery.ui.components.SegmentedChoice
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The six classes, as table columns. */
private val Classes = listOf(
    "Gents" to (ArcherClass.GENTS to AgeGroup.ADULT), "Ladies" to (ArcherClass.LADIES to AgeGroup.ADULT),
    "Jr Boys" to (ArcherClass.GENTS to AgeGroup.JUNIOR), "Jr Girls" to (ArcherClass.LADIES to AgeGroup.JUNIOR),
    "U12 Boys" to (ArcherClass.GENTS to AgeGroup.CUB), "U12 Girls" to (ArcherClass.LADIES to AgeGroup.CUB),
)

/**
 * Handicap calculator (Home tile): a score with your bow and class, and the same performance for every bow style
 * and class. Works offline once the handicap has been downloaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandicapCalculatorScreen(vm: HandicapCalculatorViewModel, onBack: () -> Unit) {
    val model by vm.model.collectAsStateWithLifecycle()
    val f by vm.form.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Handicap calculator") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val m = model
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (m == null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("The handicap hasn't been downloaded yet", style = MaterialTheme.typography.titleMedium)
                        Text("It comes from the server once (no sign-in needed), then works with no signal.", style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = { vm.download() }, enabled = !refreshing) { Text(if (refreshing) "Downloading…" else "Download now") }
                    }
                }
                return@Column
            }
            val problem = f.scoreProblem
            SectionCard(title = "Your score", subtitle = "A whole round or one day: the score and how many targets it was out of.") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = f.scoreText,
                        onValueChange = { v -> vm.update { it.copy(scoreText = v.filter(Char::isDigit).take(4)) } },
                        label = { Text("Score") },
                        singleLine = true,
                        isError = f.scoreProblem != null,
                        supportingText = problem?.let { msg -> @Composable { Text(msg) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    NumberField("Targets", f.targetsText, { v -> vm.update { it.copy(targetsText = v) } }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(18, 20, 36, 40).forEach { n -> SuggestionChip(onClick = { vm.update { it.copy(targetsText = "$n") } }, label = { Text("$n") }) }
                }
                SegmentedChoice(ArcherClass.entries, f.archerClass, { it.displayName }) { c -> vm.update { it.copy(archerClass = c) } }
                OptionDropdown("Age group", AgeGroup.entries, f.ageGroup, { it.displayName }, { g -> vm.update { it.copy(ageGroup = g) } })
                OptionDropdown("Bow style", BowStyle.entries, f.bowStyle, { it.label }, { b -> vm.update { it.copy(bowStyle = b) } })
            }
            val from = f.archer; val score = f.score; val targets = f.targets
            if (from != null && score != null && targets != null && f.scoreProblem == null) {
                val max = 24.0 * targets
                val year = m.dataThroughYear
                val ul = m.convert(score.toDouble(), max, from, year)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${ul.roundToInt()}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text("as an Unlimited Gents score on $targets targets", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("The same performance with every bow style and class", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                EquivalentTable(m, score.toDouble(), max, from, year)
                Text(
                    "From the NFAS Nationals and 3D Championships since 2002 (paper faces and 3D targets averaged), up to " +
                        "$year. Equal scores here mean the same standing among archers with that bow and class.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EquivalentTable(m: HandicapModel, score: Double, max: Double, from: HandicapModel.Archer, year: Int) {
    val scroll = rememberScrollState()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(vertical = 8.dp)) {
            // Bow names stay put; the class columns scroll sideways.
            Column {
                TableCell("Bow", 132, header = true, start = true)
                BowStyle.entries.forEach { b -> TableCell(b.displayName, 132, start = true, bold = b == from.bowStyle) }
            }
            Column(Modifier.horizontalScroll(scroll)) {
                Row { Classes.forEach { (label, _) -> TableCell(label, 76, header = true) } }
                BowStyle.entries.forEach { b ->
                    Row {
                        Classes.forEach { (_, ca) ->
                            val to = HandicapModel.Archer(b, ca.first, ca.second)
                            val v = m.convert(score, max, from, year, to = to).roundToInt()
                            val mine = to == from
                            val reference = to == HandicapModel.Archer.REFERENCE
                            TableCell("$v", 76, bold = mine || reference,
                                highlight = if (mine) MaterialTheme.colorScheme.tertiaryContainer else if (reference) MaterialTheme.colorScheme.primaryContainer else null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, widthDp: Int, header: Boolean = false, start: Boolean = false, bold: Boolean = false,
                      highlight: androidx.compose.ui.graphics.Color? = null) {
    Box(Modifier.width(widthDp.dp).let { if (highlight != null) it.background(highlight) else it }.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(
            text,
            style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (bold) FontWeight.Bold else null,
            textAlign = if (start) TextAlign.Start else TextAlign.End,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------- Admin → Handicap ----------------

private val UkDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
private fun java.time.Instant.local() = atZone(ZoneId.systemDefault()).format(UkDateTime)

/** Championships and their target counts, handicap models and the server job's runs. Admins and the Owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminHandicapScreen(vm: AdminHandicapViewModel, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val role by vm.role.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<HandicapEvent?>(null) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Handicap") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.load(showSpinner = true) }, modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val d = data) {
                Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.message, color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = { vm.load() }) { Text("Try again") }
                }
                is Load.Ready -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "fetch") {
                        SectionCard(title = "NFAS results", subtitle = "The server reads nfas.net every Monday night and refits the handicap if anything changed.") {
                            Button(onClick = { vm.fetchNow() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Fetch at the next run") }
                            Text("To read them straight away, run  FieldArcheryJobs.exe fetch  on Odin.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            d.value.runs.firstOrNull()?.let { run ->
                                HorizontalDivider()
                                Text("Last run" + (run.finishedUtc?.let { " · ${it.local()}" } ?: " · not finished"), style = MaterialTheme.typography.labelLarge)
                                run.outcome?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = if (run.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    item(key = "modelsHeader") { AdminHeader("Models") }
                    items(d.value.models, key = { "m${it.modelId}" }) { mi ->
                        Card(colors = CardDefaults.cardColors(containerColor = if (mi.status == "ACTIVE") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Model ${mi.modelId} · ${statusName(mi.status)}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${mi.fittedUtc?.local() ?: ""} · ${mi.events} championships to ${mi.dataThroughYear}, ${mi.archers} archers, ${mi.dayScores} day scores",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    String.format(Locale.UK, "Tests: hidden results %.1f points a day · bow bias %.3f", mi.holdoutRmse, mi.switcherBias),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                mi.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (mi.status != "ACTIVE" && role == AccountRole.OWNER) {
                                    OutlinedButton(onClick = { vm.activate(mi.modelId) }, enabled = !busy) { Text("Use this model") }
                                }
                            }
                        }
                    }
                    item(key = "eventsHeader") { AdminHeader("Championships") }
                    item(key = "eventsNote") {
                        Text("Targets per day aren't published: the server works them out (before Covid all 40). Tap one to set it by hand.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(d.value.events, key = { "e${it.champs}${it.year}" }) { e ->
                        Card(
                            onClick = { if (e.status == "RESULTS") editing = e },
                            colors = CardDefaults.cardColors(containerColor = if (e.targetsSource == "DOUBTFUL") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        when (e.status) {
                                            "RESULTS" -> "${e.resultCount} results"
                                            "PLACINGS" -> "Placings only, no scores"
                                            else -> "No results published"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (e.status == "RESULTS") e.targetsNote?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (e.status == "RESULTS") Column(horizontalAlignment = Alignment.End) {
                                    Text(e.targetsPerDay?.let { "$it" } ?: "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(sourceName(e.targetsSource, e.targetsSetBy), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { e ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${e.name}: targets a day") },
            text = { Text("Now ${e.targetsPerDay ?: "–"} (${sourceName(e.targetsSource, e.targetsSetBy).lowercase()}). The handicap is refitted on the next weekly run.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { editing = null; vm.setTargets(e, 36) }) { Text("36") }
                    TextButton(onClick = { editing = null; vm.setTargets(e, 40) }) { Text("40") }
                    if (e.targetsSource == "ADMIN") TextButton(onClick = { editing = null; vm.setTargets(e, null) }) { Text("Automatic") }
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AdminHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

private fun statusName(s: String) = when (s) {
    "ACTIVE" -> "in use"
    "CANDIDATE" -> "waiting (tests worse than the one in use)"
    else -> "replaced"
}

private fun sourceName(source: String?, by: String?) = when (source) {
    "RULE" -> "Before Covid"
    "AUTO" -> "Worked out"
    "DOUBTFUL" -> "Doubtful"
    "ADMIN" -> "Set by ${by ?: "an admin"}"
    else -> "Not set yet"
}
