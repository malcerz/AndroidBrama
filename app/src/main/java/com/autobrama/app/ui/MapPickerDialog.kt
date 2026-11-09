package com.autobrama.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.autobrama.app.data.GateLocationSlot
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@SuppressLint("MissingPermission")
@Composable
fun MapPickerDialog(
    slot: GateLocationSlot,
    onDismiss: () -> Unit,
    onConfirm: (latitude: Double, longitude: Double, radiusMeters: Float) -> Unit
) {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var selectedLat by remember { mutableStateOf(slot.latitude) }
    var selectedLon by remember { mutableStateOf(slot.longitude) }
    var currentRadius by remember { mutableStateOf(slot.radiusMeters) }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var markerRef by remember { mutableStateOf<Marker?>(null) }
    var circleRef by remember { mutableStateOf<Polygon?>(null) }

    // Inicjalizacja konfiguracji osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Funkcja do aktualizacji markera i okręgu na mapie
    fun updateMapOverlays(lat: Double, lon: Double, radius: Float, map: MapView) {
        val geoPoint = GeoPoint(lat, lon)

        // Marker
        val marker = markerRef ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.title = slot.name
            map.overlays.add(it)
            markerRef = it
        }
        marker.position = geoPoint

        // Circle Polygon wokół punktu
        val circle = circleRef ?: Polygon(map).also {
            it.fillPaint.color = AndroidColor.argb(60, 33, 150, 243)
            it.fillPaint.style = Paint.Style.FILL
            it.outlinePaint.color = AndroidColor.argb(200, 25, 118, 210)
            it.outlinePaint.strokeWidth = 4.0f
            it.outlinePaint.style = Paint.Style.STROKE
            map.overlays.add(circleRef ?: it)
            circleRef = it
        }
        circle.points = Polygon.pointsAsCircle(geoPoint, radius.toDouble())

        map.invalidate()
    }

    // Pobranie bieżącej pozycji GPS i ewentualne wycentrowanie
    fun centerOnGps() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        selectedLat = loc.latitude
                        selectedLon = loc.longitude
                        mapViewRef?.let { map ->
                            val pt = GeoPoint(loc.latitude, loc.longitude)
                            map.controller.setZoom(18.0)
                            map.controller.animateTo(pt)
                            updateMapOverlays(loc.latitude, loc.longitude, currentRadius, map)
                        }
                    }
                }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Mapa OpenStreetMap
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(18.0)
                            val startPoint = GeoPoint(selectedLat, selectedLon)
                            controller.setCenter(startPoint)

                            // Obsługa dotknięcia mapy (wybór nowego punktu)
                            val eventsReceiver = object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                    p?.let {
                                        selectedLat = it.latitude
                                        selectedLon = it.longitude
                                        updateMapOverlays(it.latitude, it.longitude, currentRadius, this@apply)
                                    }
                                    return true
                                }

                                override fun longPressHelper(p: GeoPoint?): Boolean {
                                    return false
                                }
                            }
                            overlays.add(0, MapEventsOverlay(eventsReceiver))

                            mapViewRef = this
                            updateMapOverlays(selectedLat, selectedLon, currentRadius, this)

                            // Automatyczne wycentrowanie na GPS przy pierwszym otwarciu
                            centerOnGps()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Górny pasek z tytułem i współrzędnymi
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .align(Alignment.TopCenter),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Wybór punktu: ${slot.name}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Zamknij")
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Dotknij mapy palcem, aby ustawić lokalizację bramy.",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Wybrano: %.5f, %.5f".format(selectedLat, selectedLon),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Pływający przycisk "Wycentruj na GPS"
                FloatingActionButton(
                    onClick = { centerOnGps() },
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 190.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Moja pozycja GPS")
                }

                // Dolny panel zatwierdzenia z regulacją promienia
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .align(Alignment.BottomCenter),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Promień strefy aktywacji:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${currentRadius.toInt()} m",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = currentRadius,
                            onValueChange = { newRadius ->
                                currentRadius = newRadius
                                mapViewRef?.let { map ->
                                    updateMapOverlays(selectedLat, selectedLon, newRadius, map)
                                }
                            },
                            valueRange = 20f..500f,
                            steps = 47,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Anuluj")
                            }

                            Button(
                                onClick = {
                                    onConfirm(selectedLat, selectedLon, currentRadius)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1.5f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Zatwierdź punkt")
                            }
                        }
                    }
                }
            }
        }
    }
}
