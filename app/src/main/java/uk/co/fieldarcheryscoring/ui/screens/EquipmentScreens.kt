package uk.co.fieldarcheryscoring.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fieldarcheryscoring.domain.BowStyle
import uk.co.fieldarcheryscoring.domain.BowType
import uk.co.fieldarcheryscoring.domain.Equipment
import uk.co.fieldarcheryscoring.domain.FletchMaterial
import uk.co.fieldarcheryscoring.ui.ArrowsEditViewModel
import uk.co.fieldarcheryscoring.ui.BowEditViewModel
import uk.co.fieldarcheryscoring.ui.EquipmentViewModel
import uk.co.fieldarcheryscoring.ui.components.BottomActionBar
import uk.co.fieldarcheryscoring.ui.components.OptionDropdown
import uk.co.fieldarcheryscoring.ui.components.SectionCard

/**
 * Equipment (3.2.24, Pete): your bows (type, make, model, poundage, the bow styles you shoot each in) and arrows (make,
 * model, length, weights, spine, fletches, the bows you use them with). New round offers them for the bow style picked.
 * Synced with your account; only you see them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentScreen(vm: EquipmentViewModel, onBack: () -> Unit, onBow: (String) -> Unit, onArrows: (String) -> Unit) {
    val bows by vm.bows.collectAsStateWithLifecycle()
    val arrows by vm.arrows.collectAsStateWithLifecycle()
    val allBows by vm.allBows.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equipment") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "New round offers the bows for the bow style you pick, and the arrows for that bow, starting on the ones you " +
                    "used last time. With only one, it's used without asking. Only you see your equipment. How you shoot with each " +
                    "bow and set of arrows is in Stats → Analysis.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionCard(title = "Bows") {
                val list = bows.orEmpty()
                if (list.isEmpty()) Text("No bows yet.", style = MaterialTheme.typography.bodyMedium)
                list.forEach { b ->
                    EquipmentRow(
                        title = Equipment.name(b),
                        lines = listOf(Equipment.detail(b), if (b.bowStyles.isEmpty()) "No bow style: not offered for rounds" else b.bowStyles.sortedBy { it.ordinal }.joinToString(", ") { it.displayName }),
                        onClick = { onBow(b.id) },
                    )
                }
                OutlinedButton(onClick = { onBow("new") }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(" Add a bow")
                }
            }
            SectionCard(title = "Arrows") {
                val list = arrows.orEmpty()
                if (list.isEmpty()) Text("No arrows yet.",
                    style = MaterialTheme.typography.bodyMedium)
                list.forEach { a ->
                    val used = a.bowIds.mapNotNull { id -> allBows.firstOrNull { it.id == id } }
                        .joinToString(", ") { Equipment.name(it) + if (it.deleted) " (deleted)" else "" }
                    EquipmentRow(
                        title = Equipment.name(a),
                        lines = listOf(Equipment.detail(a), if (used.isEmpty()) "No bow: not offered for rounds" else "With $used").filter { it.isNotEmpty() },
                        onClick = { onArrows(a.id) },
                    )
                }
                OutlinedButton(onClick = { onArrows("new") }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(" Add arrows")
                }
            }
        }
    }
}

@Composable
private fun EquipmentRow(title: String, lines: List<String>, onClick: () -> Unit) {
    // 3.2.29 (Pete): no figures here (arrows used with two bows would mix them); Stats → Analysis → By equipment has them.
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            lines.filter { it.isNotBlank() }.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


@Composable
private fun TextInput(
    label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier,
    number: Boolean = false, supporting: String? = null, isError: Boolean = false, maxLength: Int = Equipment.MAX_TEXT,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(maxLength)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal)
        else KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Problems(problems: List<String>) {
    problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ConfirmDelete(what: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $what?") },
        text = { Text("It's no longer offered for new rounds. Rounds you shot with it keep its name.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BowEditScreen(vm: BowEditViewModel, onBack: () -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    val tried by vm.tried.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Add a bow" else "Bow") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (!vm.isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete") } },
            )
        },
        bottomBar = { BottomActionBar(text = "Save", enabled = form != null) { vm.save(onBack) } },
    ) { padding ->
        val f = form ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "The bow") {
                OptionDropdown(
                    label = "Type", options = BowType.entries, selected = f.type, optionLabel = { it.displayName },
                    onSelect = { t -> vm.update { it.copy(type = t) } },
                )
                TextInput("Make", f.make, { v -> vm.update { it.copy(make = v) } })
                TextInput("Model (optional)", f.model, { v -> vm.update { it.copy(model = v) } })
                TextInput("Poundage (lb, optional)", f.poundage, { v -> vm.update { it.copy(poundage = v) } }, number = true,
                    supporting = "At your draw length, e.g. 45 or 47.5")
            }
            SectionCard(title = "Bow styles", subtitle = "The styles you shoot it in. New round offers it for these (none: kept here, not offered).") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BowStyle.entries.forEach { s ->
                        FilterChip(
                            selected = s in f.bowStyles,
                            onClick = { vm.update { it.copy(bowStyles = if (s in it.bowStyles) it.bowStyles - s else it.bowStyles + s) } },
                            label = { Text(s.label) },
                        )
                    }
                }
                // 3.2.27 (Pete): unlinked equipment is kept, but say it won't be offered.
                if (f.bowStyles.isEmpty()) UnlinkedWarning("No bow style picked: this bow won't be available when you start a round until you pick one.")
            }
            if (tried) Problems(f.problems())
        }
    }
    if (confirmDelete) ConfirmDelete("this bow", onDelete = { confirmDelete = false; vm.delete(onBack) }, onDismiss = { confirmDelete = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrowsEditScreen(vm: ArrowsEditViewModel, onBack: () -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    val tried by vm.tried.collectAsStateWithLifecycle()
    val bows by vm.bows.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Add arrows" else "Arrows") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (!vm.isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete") } },
            )
        },
        bottomBar = { BottomActionBar(text = "Save", enabled = form != null) { vm.tidySpine(); vm.save(onBack) } },
    ) { padding ->
        val f = form ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "The arrows") {
                TextInput("Make", f.make, { v -> vm.update { it.copy(make = v) } })
                TextInput("Model (optional)", f.model, { v -> vm.update { it.copy(model = v) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("Length (in)", f.length, { v -> vm.update { it.copy(length = v) } }, Modifier.weight(1f), number = true)
                    TextInput("Arrow weight (gr)", f.arrowWeight, { v -> vm.update { it.copy(arrowWeight = v) } }, Modifier.weight(1f), number = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The spine is tidied into the usual way of writing it when you leave the box.
                    val spine = Equipment.spine(f.spine)
                    val bad = f.spine.isNotBlank() && spine == null
                    TextInput(
                        "Spine", f.spine, { v -> vm.update { it.copy(spine = v) } },
                        Modifier.weight(1f).onFocusChanged { if (!it.isFocused) vm.tidySpine() },
                        supporting = when {
                            bad -> "e.g. 500, 45-50 or 2213"
                            spine != null -> "${spine.kind.displayName}: ${spine.text}"
                            else -> "500 · 45-50 · 2213"
                        },
                        isError = bad, maxLength = 12,
                    )
                    TextInput("Point (gr)", f.pointWeight, { v -> vm.update { it.copy(pointWeight = v) } }, Modifier.weight(1f), number = true)
                }
            }
            SectionCard(title = "Fletches") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput("How many", f.fletchCount, { v -> vm.update { it.copy(fletchCount = v) } }, Modifier.weight(1f), number = true, maxLength = 2)
                    TextInput("Length (in)", f.fletchLength, { v -> vm.update { it.copy(fletchLength = v) } }, Modifier.weight(1f), number = true, maxLength = 6)
                }
                OptionDropdown(
                    label = "Material", options = FletchMaterial.entries, selected = f.fletchMaterial, optionLabel = { it.displayName },
                    onSelect = { m -> vm.update { it.copy(fletchMaterial = m) } }, noneLabel = "Not given",
                )
                TextInput("Colours", f.fletchColours, { v -> vm.update { it.copy(fletchColours = v) } },
                    maxLength = Equipment.MAX_COLOURS, supporting = "e.g. barred grey, white cock feather")
            }
            SectionCard(title = "Used with", subtitle = "The bows you shoot them from. New round offers them for these (none: kept here, not offered).") {
                if (bows.isEmpty()) Text("No bows yet.", style = MaterialTheme.typography.bodyMedium)
                bows.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.update { it.copy(bowIds = if (b.id in it.bowIds) it.bowIds - b.id else it.bowIds + b.id) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = b.id in f.bowIds, onCheckedChange = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(Equipment.label(b) + if (b.deleted) " (deleted)" else "", style = MaterialTheme.typography.bodyLarge)
                            Text(Equipment.detail(b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (f.bowIds.isEmpty()) UnlinkedWarning(
                    if (bows.isEmpty()) "No bow yet: these arrows won't be available when you start a round until you add a bow and pick it here."
                    else "No bow picked: these arrows won't be available when you start a round until you pick one."
                )
            }
            if (tried) Problems(f.problems())
        }
    }
    if (confirmDelete) ConfirmDelete("these arrows", onDelete = { confirmDelete = false; vm.delete(onBack) }, onDismiss = { confirmDelete = false })
}

/** 3.2.27 (Pete): a bow with no bow style, or arrows with no bow, are kept but not offered for rounds until linked. */
@Composable
private fun UnlinkedWarning(text: String) {
    Text("⚠ $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
