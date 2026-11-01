package uk.co.fnurble.nfas.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.fnurble.nfas.domain.LocationStatus
import uk.co.fnurble.nfas.domain.RoundLocation
import uk.co.fnurble.nfas.ui.Load
import uk.co.fnurble.nfas.ui.RoundLocationViewModel
import java.util.Locale

/**
 * One line showing where the round is: "Bowmen of X · Not here?", "New location – tap to name it",
 * "Finding location…" or "No location · Add". Tap opens the location sheet.
 */
@Composable
fun LocationChip(location: RoundLocation, signedIn: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val needsName = location.status == LocationStatus.NEEDS_NAME
    val (text, action) = when (location.status) {
        LocationStatus.SET -> (location.name ?: "Location set") to "Not here?"
        LocationStatus.NEEDS_NAME -> "New location" to "Name it"
        LocationStatus.LOOKING -> (if (signedIn) "Finding location… (needs signal)" else "Position saved – sign in to find the location") to null
        LocationStatus.NONE -> "No location" to if (signedIn) "Add" else null
    }
    Surface(
        color = if (needsName) scheme.tertiaryContainer else scheme.surfaceContainerLow,
        contentColor = if (needsName) scheme.onTertiaryContainer else scheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().clickable(enabled = signedIn, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (action != null) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = if (needsName) scheme.onTertiaryContainer else scheme.primary)
            }
        }
    }
}

/**
 * Location sheet: pick a nearby location ("Not here?"), name a new one at the round's position, or
 * take a position now if the round has none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSheet(vm: RoundLocationViewModel, current: RoundLocation, onDismiss: () -> Unit) {
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val hasFix by vm.hasFix.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val taken by vm.taken.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.captureNow()
    }

    LaunchedEffect(Unit) { vm.load() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (current.status == LocationStatus.SET) "Shoot location" else "Where are you shooting?",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (current.status == LocationStatus.SET) {
                Text("Now: ${current.name}", style = MaterialTheme.typography.bodyLarge)
            }

            if (!hasFix) {
                Text(
                    "This round has no GPS position. If you're at the shoot now, get your position to find or name the location.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        if (vm.hasPermission) vm.captureNow()
                        else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Getting position…" else "Get position now") }
                return@Column
            }

            // Nearby locations (within 10 km), nearest first.
            when (val n = nearby) {
                Load.Loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Load.Failed -> {
                    Text(n.message, color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = { vm.load() }) { Text("Try again") }
                }
                is Load.Ready -> {
                    val others = n.value.filter { it.name != current.name }
                    if (others.isNotEmpty()) {
                        Text("Nearby", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Column {
                            others.forEach { loc ->
                                ListItem(
                                    headlineContent = { Text(loc.name) },
                                    supportingContent = { Text(distance(loc.distanceMetres)) },
                                    leadingContent = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    modifier = Modifier.clickable(enabled = !busy) { vm.choose(loc, onDismiss) },
                                )
                                HorizontalDivider()
                            }
                        }
                    } else if (current.status != LocationStatus.SET) {
                        Text(
                            "No shoot locations known nearby. You're the first to score here – give it a name.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text("Name a new location", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Club or ground name") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.trim().length >= 2) vm.name(name, onDismiss) }),
                modifier = Modifier.fillMaxWidth(),
            )
            taken?.let { existing ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("There's already a location called \"${existing.name}\".")
                        TextButton(onClick = { vm.choose(existing, onDismiss) }, enabled = !busy) { Text("Use ${existing.name}") }
                    }
                }
            }
            Button(
                onClick = { vm.name(name, onDismiss) },
                enabled = !busy && name.trim().length >= 2,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save new location") }
            Text(
                "Only your shoot location is shared, never your own position. Admins can correct names later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun distance(metres: Int): String =
    if (metres < 1000) "$metres m away"
    else String.format(Locale.UK, "%.1f km away", metres / 1000.0)

/** Location line for headers and lists, or null if the round has none. */
fun RoundLocation.label(): String? = when (status) {
    LocationStatus.SET -> name
    LocationStatus.NEEDS_NAME -> "New location (not named yet)"
    else -> null
}
