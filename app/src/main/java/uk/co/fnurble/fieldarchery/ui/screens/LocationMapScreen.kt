package uk.co.fnurble.fieldarchery.ui.screens

import android.graphics.drawable.Drawable
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import uk.co.fnurble.fieldarchery.data.MapLocation
import uk.co.fnurble.fieldarchery.ui.Load
import uk.co.fnurble.fieldarchery.ui.LocationMapViewModel

/**
 * Map of shoot locations (OpenStreetMap). Locations you've shot at are in the primary colour, others grey;
 * your private ones are included. Tap a pin for its name, tap the name for the location page.
 * Name-only locations (3.0, no map pin) are listed from the "No map pin" button. Place mode puts one on the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationMapScreen(
    vm: LocationMapViewModel,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onOpenLocation: (MapLocation) -> Unit,
    /** Choosing a location for a round: tapping a pin's pop-up picks it instead of opening its page. */
    pickMode: Boolean = false,
    /** Pick mode: the round being given a location (tap the map to name a new one there). */
    roundId: String? = null,
    /** Pick mode: called after a new location has been named and given to the round. */
    onPicked: () -> Unit = {},
    /** Place mode (3.0): the name-only location to put on the map where the archer taps. */
    placeId: String? = null,
    placeName: String? = null,
    /** Place mode: called once it's on the map. */
    onPlaced: () -> Unit = {},
) {
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val locations by vm.locations.collectAsStateWithLifecycle()
    val taken by vm.taken.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val named by vm.named.collectAsStateWithLifecycle()
    var showNamed by remember { mutableStateOf(false) }
    var newAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    /** "New location" tapped: the next tap on the map is where it goes. */
    var placing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    LaunchedEffect(signedIn) { if (signedIn) vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (placeId != null) "Put it on the map" else if (pickMode) "Choose the shoot location" else "Shoot locations") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!pickMode && placeId == null && signedIn && !placing && locations is Load.Ready) {
                ExtendedFloatingActionButton(
                    onClick = { placing = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New location") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !signedIn -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Sign in to see shoot locations", style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = onProfile) { Text("Go to Profile") }
                }
                else -> when (val l = locations) {
                    Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is Load.Failed -> Card(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(l.message, color = MaterialTheme.colorScheme.onErrorContainer)
                            FilledTonalButton(onClick = { vm.load() }) { Text("Try again") }
                        }
                    }
                    is Load.Ready -> {
                        LocationMap(
                            l.value, onOpenLocation, pickMode,
                            onMapTap = if ((pickMode && roundId != null) || placing || placeId != null) { lat, lon -> newAt = lat to lon } else null,
                        )
                        if (placeId != null) {
                            Surface(
                                Modifier.align(Alignment.TopCenter).padding(12.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shadowElevation = 2.dp,
                            ) {
                                Text("Tap the map where ${placeName ?: "the shoot"} is.", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                        } else if (named.isNotEmpty() && !placing) {
                            // Name-only locations aren't on the map: a button lists them.
                            FilledTonalButton(
                                onClick = { showNamed = true },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).padding(bottom = if (pickMode) 0.dp else 72.dp),
                            ) { Text("No map pin (${named.size})") }
                        }
                        Legend(Modifier.align(Alignment.BottomStart).padding(12.dp), shot = l.value.count { it.myRounds > 0 }, total = l.value.size)
                        if (placing) {
                            Surface(
                                Modifier.align(Alignment.TopCenter).padding(12.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shadowElevation = 2.dp,
                            ) {
                                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Tap the map where the new location is.", style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f, fill = false))
                                    TextButton(onClick = { placing = false }) { Text("Cancel") }
                                }
                            }
                        }
                        if (pickMode) {
                            Surface(
                                Modifier.align(Alignment.TopCenter).padding(12.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shadowElevation = 2.dp,
                            ) {
                                Text(
                                    "Tap a pin, then its name, to use it. Or tap the map where the shoot is to name a new location.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNamed) {
        ModalBottomSheet(onDismissRequest = { showNamed = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No map pin", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Shoots known by name only, e.g. championships from years ago." + if (pickMode) " Tap one to use it for this round." else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(named, key = { it.id }) { loc ->
                        ListItem(
                            headlineContent = { Text(loc.name + if (loc.isPrivate) " (private)" else "") },
                            supportingContent = {
                                Text((if (loc.myRounds > 0) "You: ${loc.myRounds}" else "Not shot") + if (loc.isPrivate) "" else " · everyone: ${loc.rounds}")
                            },
                            modifier = Modifier.clickable { showNamed = false; onOpenLocation(loc) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val point = newAt
    if (point != null && placeId != null) {
        AlertDialog(
            onDismissRequest = { newAt = null },
            title = { Text("Put it on the map") },
            text = {
                Text(
                    "Put ${placeName ?: "this location"} at the point you tapped? From then on it's on the map and phones scoring " +
                        "there are matched to it. It can't be moved afterwards (an Admin can merge it into another location).",
                )
            },
            confirmButton = { TextButton(onClick = { vm.place(placeId, point.first, point.second) { newAt = null; onPlaced() } }) { Text("Put it here") } },
            dismissButton = { TextButton(onClick = { newAt = null }) { Text("Cancel") } },
        )
    } else if (point != null && roundId != null) {
        NewLocationDialog(
            taken = taken?.name,
            onDismiss = { newAt = null; vm.clearTaken() },
            onSave = { name, private -> vm.createAt(roundId, name, point.first, point.second, private) { newAt = null; onPicked() } },
            onUseExisting = { taken?.let { t -> vm.chooseExisting(roundId, t) { newAt = null; vm.clearTaken(); onPicked() } } },
        )
    } else if (point != null && placing) {
        // Not for a round: just add the location (a name already used comes back as a message).
        NewLocationDialog(
            taken = null,
            onDismiss = { newAt = null; placing = false },
            onSave = { name, private -> vm.createNew(name, point.first, point.second, private) { newAt = null; placing = false } },
            onUseExisting = {},
            forRound = false,
        )
    }
}

/** Name a new location at the point tapped on the map. */
@Composable
private fun NewLocationDialog(
    taken: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, private: Boolean) -> Unit,
    onUseExisting: () -> Unit,
    forRound: Boolean = true,
) {
    var name by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New shoot location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (forRound) "Name the location at the point you tapped. It's used for this round."
                    else "Name the location at the point you tapped.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Club or ground name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Private", style = MaterialTheme.typography.bodyLarge)
                        Text("Only you see it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = private, onCheckedChange = { private = it })
                }
                if (taken != null) {
                    Text("There's already a location called \"$taken\".", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onUseExisting) { Text("Use $taken") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, private) }, enabled = name.trim().length >= 2) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LocationMap(
    locations: List<MapLocation>,
    onOpenLocation: (MapLocation) -> Unit,
    pickMode: Boolean,
    onMapTap: ((lat: Double, lon: Double) -> Unit)? = null,
) {
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shotColour = MaterialTheme.colorScheme.primary.toArgb()
    val otherColour = Color(0xFF8A8A8A).toArgb()
    val fitted = remember { booleanArrayOf(false) }   // zoom to the pins once, not on every update
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            minZoomLevel = 4.0
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(54.0, -2.5))   // Great Britain until the pins are placed
            // Taps on the map itself (pins get theirs first): close any pop-up; in pick mode, name a new location there.
            overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    InfoWindow.closeAllInfoWindowsOn(this@apply)
                    val tap = currentOnMapTap ?: return false
                    tap(p.latitude, p.longitude)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            }))
        }
    }

    // osmdroid needs its own resume / pause.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { map ->
            map.overlays.removeAll { it is Marker }
            locations.forEach { loc ->
                val lat = loc.latitude ?: return@forEach   // only pinned locations come here
                val lon = loc.longitude ?: return@forEach
                val marker = Marker(map).apply {
                    position = GeoPoint(lat, lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = tinted(icon, if (loc.myRounds > 0) shotColour else otherColour)
                    title = loc.name + if (loc.isPrivate) " (private)" else ""
                    snippet = buildString {
                        append(if (loc.myRounds > 0) "You: ${loc.myRounds} round${if (loc.myRounds == 1) "" else "s"}" else "Not shot here yet")
                        if (!loc.isPrivate) append(" · everyone: ${loc.rounds}")
                    }
                    subDescription = if (pickMode) "Tap here to use this location" else "Tap here for scorecards and stats"
                    setOnMarkerClickListener { m, _ ->
                        if (m.isInfoWindowShown) {
                            m.closeInfoWindow()
                            onOpenLocation(loc)
                        } else {
                            m.showInfoWindow()
                            // osmdroid shares one pop-up between all pins and its own touch handler just closes it,
                            // so point the pop-up at this location each time it opens: a tap on it opens the page.
                            m.infoWindow?.view?.setOnTouchListener { v, event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    v.performClick()
                                    m.closeInfoWindow()
                                    onOpenLocation(loc)
                                }
                                true
                            }
                        }
                        true
                    }
                }
                map.overlays.add(marker)
            }
            // First time: zoom to fit the pins (shot-at ones if there are any).
            val pinned = locations.filter { it.hasPin }
            if (!fitted[0] && pinned.isNotEmpty()) {
                val focus = pinned.filter { it.myRounds > 0 }.ifEmpty { pinned }
                if (focus.size == 1) {
                    map.controller.setZoom(12.0)
                    map.controller.setCenter(GeoPoint(focus[0].latitude!!, focus[0].longitude!!))
                } else {
                    val box = BoundingBox.fromGeoPoints(focus.map { GeoPoint(it.latitude!!, it.longitude!!) })
                    map.post { map.zoomToBoundingBox(box.increaseByScale(1.3f), false, 64) }
                }
                fitted[0] = true
            }
            map.invalidate()
        },
    )
}

/** osmdroid's own pin, recoloured. */
private fun tinted(pin: Drawable?, colour: Int): Drawable? =
    pin?.constantState?.newDrawable()?.mutate()?.let { d -> DrawableCompat.wrap(d).also { DrawableCompat.setTint(it, colour) } }

@Composable
private fun Legend(modifier: Modifier, shot: Int, total: Int) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendRow(MaterialTheme.colorScheme.primary, "Shot here ($shot)")
            LegendRow(Color(0xFF8A8A8A), "Not yet (${total - shot})")
            Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegendRow(colour: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = CircleShape, color = colour, modifier = Modifier.size(12.dp)) {}
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
