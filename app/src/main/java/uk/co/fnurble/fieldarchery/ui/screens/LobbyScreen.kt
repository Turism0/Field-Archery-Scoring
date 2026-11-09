package uk.co.fnurble.fieldarchery.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import uk.co.fnurble.fieldarchery.data.LiveGroupRepository
import uk.co.fnurble.fieldarchery.data.LiveMember
import uk.co.fnurble.fieldarchery.data.LiveMethod
import uk.co.fnurble.fieldarchery.ui.LobbyViewModel
import uk.co.fnurble.fieldarchery.ui.components.AskForNearby
import uk.co.fnurble.fieldarchery.ui.components.LiveLinkNotes
import uk.co.fnurble.fieldarchery.ui.components.SectionCard

/**
 * A live group before scoring starts: the join code and QR for the others, who's in, and (host) Start / Cancel.
 * Every phone opens scoring by itself once the host starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    vm: LobbyViewModel,
    onBack: () -> Unit,
    /** Scoring has started (or had already): open scoring on this phone's round. */
    onStarted: (roundId: String) -> Unit,
    /** Cancelled or left: back to home. */
    onGone: () -> Unit,
) {
    val poll by vm.poll.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmCancel by remember { mutableStateOf(false) }
    val s = poll?.state
    AskForNearby()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    // Started (or finished while away): go straight to scoring, once.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(s?.status) {
        if (!opened && s != null && (s.started || s.closed)) {
            vm.myRoundId?.let { opened = true; onStarted(it) } ?: run { if (s.closed) onGone() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Live group") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    poll?.linkNote()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                title = "Join code",
                subtitle = "Others tap Score a round → Join a live group, then scan this or type the code. Works for 24 hours. " +
                    "With no signal they can still join, near this phone (Bluetooth on).",
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        s.code.chunked(3).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp,
                        letterSpacing = 4.sp,
                    )
                    val qr = remember(s.code) { qrBitmap(LiveGroupRepository.qrText(s.code)) }
                    // White behind the code so it scans in dark mode too.
                    Surface(color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(top = 8.dp)) {
                        Image(qr, contentDescription = "QR code to join", modifier = Modifier.size(220.dp).padding(8.dp))
                    }
                }
            }
            SectionCard(
                title = "Scoring",
                subtitle = LiveMethod.fromCode(s.method)?.let { "${it.displayName}: ${it.description}" },
            ) {
                Text(
                    "${s.roundType.displayName} · ${s.targetCount} targets" + (s.locationName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SectionCard(title = "In the group (${s.members.size})", subtitle = "In scoring order.") {
                s.members.sortedBy { it.sortOrder }.forEach { m -> MemberLine(m, isMe = m.memberId == s.myMemberId) }
            }
            // No signal: phone to phone instead; Bluetooth off etc. (2.3)
            poll?.let { LiveLinkNotes(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (s.isHost) {
                Button(
                    onClick = { vm.start() },
                    enabled = !busy && !s.started && !s.closed,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Starting…" else "Start scoring") }
                Text(
                    "Start once everyone has joined. Anyone joining later can still use the code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { confirmCancel = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel the group")
                }
            } else {
                Text(
                    "Waiting for the host to start. Your phone opens scoring by itself.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { confirmCancel = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Leave the group")
                }
            }
        }
    }

    if (confirmCancel && s != null) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(if (s.isHost) "Cancel the group?" else "Leave the group?") },
            text = {
                Text(
                    if (s.isHost) "The code stops working and the group's rounds are deleted from this phone."
                    else "Your round in this group is deleted from this phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; vm.cancel(onGone) }) { Text(if (s.isHost) "Cancel group" else "Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun MemberLine(m: LiveMember, isMe: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(if (isMe) "${m.name} (you)" else m.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${m.archerClass.displayName} · ${m.ageGroup.displayName} · ${m.bowStyle.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when (m.kind) {
                "HOST" -> "Host"
                "JOINED" -> "Own phone"
                "FRIEND" -> "Friend"
                else -> "Guest"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A QR code for [text] as an image (black on white). */
private fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
