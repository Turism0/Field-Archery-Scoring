package uk.co.fieldarcheryscoring.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import uk.co.fieldarcheryscoring.domain.AgeGroup
import uk.co.fieldarcheryscoring.domain.ArcherClass
import uk.co.fieldarcheryscoring.domain.BowStyle
import uk.co.fieldarcheryscoring.ui.JoinViewModel
import uk.co.fieldarcheryscoring.ui.components.BottomActionBar
import uk.co.fieldarcheryscoring.ui.components.AskForNearby
import uk.co.fieldarcheryscoring.ui.components.OptionDropdown
import uk.co.fieldarcheryscoring.ui.components.SectionCard
import uk.co.fieldarcheryscoring.ui.components.SegmentedChoice

/** Join someone's live group: scan their QR or type the code, check your class / age group / bow, join. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(
    vm: JoinViewModel,
    onBack: () -> Unit,
    /** Joined: open the group's lobby. */
    onJoined: (groupId: String) -> Unit,
) {
    val f by vm.form.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scanProblem by remember { mutableStateOf<String?>(null) }
    // With no signal, joining is phone to phone: that needs Nearby devices.
    AskForNearby()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join a live group") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            BottomActionBar(
                text = if (f.busy) "Joining…" else "Join",
                enabled = f.isValid && !f.busy,
                onClick = { vm.join(onJoined) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                title = "Group code",
                subtitle = "Scan the QR on the host's phone, or type the 6-character code. You need to be signed in. With no " +
                    "signal, stand near the host's phone with Bluetooth on.",
            ) {
                FilledTonalButton(
                    onClick = {
                        scanProblem = null
                        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                        GmsBarcodeScanning.getClient(context, options).startScan()
                            .addOnSuccessListener { code -> code.rawValue?.let { vm.onCode(it) } }
                            .addOnFailureListener { e -> scanProblem = "Couldn't scan: ${e.message ?: "try typing the code"}" }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR code") }
                scanProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = f.code,
                    onValueChange = { vm.onCode(it) },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SectionCard(title = "You", subtitle = "Filled in from your profile. Changes here only affect this round.") {
                SegmentedChoice(ArcherClass.entries, f.archerClass, { it.displayName }) { c -> vm.update { it.copy(archerClass = c) } }
                OptionDropdown(
                    label = "Age group",
                    options = AgeGroup.entries,
                    selected = f.ageGroup,
                    optionLabel = { it.displayName },
                    onSelect = { g -> vm.update { it.copy(ageGroup = g) } },
                )
                OptionDropdown(
                    label = "Bow style",
                    options = BowStyle.entries,
                    selected = f.bowStyle,
                    optionLabel = { it.label },
                    onSelect = { b -> vm.update { it.copy(bowStyle = b) } },
                )
            }
            if (f.busy) {
                Text(
                    "Looking for the group, on the server and phone to phone…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            f.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
