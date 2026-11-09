package uk.co.fnurble.fieldarchery.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import uk.co.fnurble.fieldarchery.data.LivePoll
import uk.co.fnurble.fieldarchery.data.NearbyLink

/**
 * Asks once for "Nearby devices" (location on older phones) so live groups can share scores phone to phone with no
 * signal (2.3). Put on the join, lobby and scoring screens.
 */
@Composable
fun AskForNearby() {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (!asked && !NearbyLink.hasPermissions(context)) {
            asked = true
            launcher.launch(NearbyLink.permissions())
        }
    }
}

/**
 * Live groups: how this phone is keeping in touch with the others when there's no signal, and (Pete, 2.3) a nudge with
 * a button when Bluetooth or Wi-Fi is off or Nearby devices isn't allowed, so phone to phone can work. Scoring carries
 * on either way.
 */
@Composable
fun LiveLinkNotes(poll: LivePoll, color: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val n = poll.nearby
    val s = poll.state
    if (s?.closed == true) return
    Column {
        poll.linkNote()?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        val off = buildList {
            if (n.needsPermission) add("Nearby devices isn't allowed")
            if (n.bluetoothOff) add("Bluetooth is off")
            if (n.wifiOff) add("Wi-Fi is off")
        }
        if (off.isNotEmpty()) {
            Text(
                off.joinToString(", ").replaceFirstChar { it.uppercase() } +
                    ": turn ${if (off.size > 1) "them" else "it"} on so phones can share scores with no signal" +
                    (if (n.bluetoothOff) "" else " (Wi-Fi makes it faster)") + ".",
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
            Row {
                if (n.needsPermission) TextButton(onClick = { launcher.launch(NearbyLink.permissions()) }) { Text("Allow Nearby devices") }
                if (n.bluetoothOff) TextButton(onClick = { open(context, Settings.ACTION_BLUETOOTH_SETTINGS) }) { Text("Turn on Bluetooth") }
                if (n.wifiOff) {
                    TextButton(onClick = {
                        open(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
                    }) { Text("Turn on Wi-Fi") }
                }
            }
        }
    }
}

private fun open(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
