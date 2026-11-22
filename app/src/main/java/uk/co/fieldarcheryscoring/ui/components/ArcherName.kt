package uk.co.fieldarcheryscoring.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import uk.co.fieldarcheryscoring.domain.ArcherNames

/**
 * Name another archer (3.2.23, Pete): what you call [username] on this phone. Only you see it; it's never sent anywhere.
 * Saving a blank name goes back to their username.
 */
@Composable
fun NameArcherDialog(username: String, current: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Name $username" else "Change name") },
        text = {
            Column {
                Text(
                    "What you call $username. It's shown on this phone instead of their username, and only you see it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ArcherNames.MAX_LENGTH) },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ArcherNames.clean(text)) }) { Text("Save") } },
        dismissButton = {
            if (current != null) TextButton(onClick = { onSave(null) }) { Text("Use username") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
