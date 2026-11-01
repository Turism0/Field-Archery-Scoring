package uk.co.fnurble.nfas.ui.screens

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
import uk.co.fnurble.nfas.data.MapLocation
import uk.co.fnurble.nfas.ui.Load
import uk.co.fnurble.nfas.ui.LocationMapViewModel

/**
 * Map of shoot locations (OpenStreetMap). Locations you've shot at are in the primary colour, others grey;
 * your private ones are included. Tap a pin for its name, tap the name for the location page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationMapScreen(
    vm: LocationMapViewModel,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onOpenLocation: (MapLocation) -> Unit,
) {
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val locations by vm.locations.collectAsStateWithLifecycle()

    LaunchedEffect(signedIn) { if (signedIn) vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shoot locations") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
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
                        LocationMap(l.value, onOpenLocation)
                        Legend(Modifier.align(Alignment.BottomStart).padding(12.dp), shot = l.value.count { it.myRounds > 0 }, total = l.value.size)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationMap(locations: List<MapLocation>, onOpenLocation: (MapLocation) -> Unit) {
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
                val marker = Marker(map).apply {
                    position = GeoPoint(loc.latitude, loc.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = tinted(icon, if (loc.myRounds > 0) shotColour else otherColour)
                    title = loc.name + if (loc.isPrivate) " (private)" else ""
                    snippet = buildString {
                        append(if (loc.myRounds > 0) "You: ${loc.myRounds} round${if (loc.myRounds == 1) "" else "s"}" else "Not shot here yet")
                        if (!loc.isPrivate) append(" · everyone: ${loc.rounds}")
                    }
                    subDescription = "Tap here for scorecards and stats"
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
            if (!fitted[0] && locations.isNotEmpty()) {
                val focus = locations.filter { it.myRounds > 0 }.ifEmpty { locations }
                if (focus.size == 1) {
                    map.controller.setZoom(12.0)
                    map.controller.setCenter(GeoPoint(focus[0].latitude, focus[0].longitude))
                } else {
                    val box = BoundingBox.fromGeoPoints(focus.map { GeoPoint(it.latitude, it.longitude) })
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
