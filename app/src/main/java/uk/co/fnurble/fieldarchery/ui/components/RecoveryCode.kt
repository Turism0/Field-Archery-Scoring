package uk.co.fnurble.fieldarchery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A recovery code to write down (2.4): shown once, after making an account, a password reset, or from Profile. The
 * server only keeps a hash of it, so it can't be shown again. Closes once "I've saved it" is ticked.
 */
@Composable
fun RecoveryCodeDialog(code: String, onDone: () -> Unit) {
    var saved by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Your recovery code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "If you forget your password, this code lets you set a new one. Write it down or take a screenshot " +
                        "and keep it safe: it can't be shown again. It works once (you get a new one then).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer {
                    Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp)
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saved, onCheckedChange = { saved = it })
                    Text("I've saved it", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone, enabled = saved) { Text("Done") } },
    )
}
