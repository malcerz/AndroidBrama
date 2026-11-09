package com.autobrama.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.car.app.connection.CarConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.autobrama.app.data.GateLocationSlot
import com.autobrama.app.statemachine.GateZoneState
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1976D2),
                    secondary = Color(0xFF009688),
                    surface = Color(0xFFF8F9FA)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    AutoBramaScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoBramaScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val isAutomationEnabled by viewModel.isAutomationEnabled.collectAsState()
    val requireAndroidAuto by viewModel.requireAndroidAuto.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val lastCallTimestamp by viewModel.lastCallTimestamp.collectAsState()

    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val closestGateStatus by viewModel.closestGateStatus.collectAsState()
    val gateStatuses by viewModel.gateStatuses.collectAsState()
    val carConnectionType by viewModel.carConnectionType.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var slotForMapDialog by remember { mutableStateOf<GateLocationSlot?>(null) }

    // Uprawnienia
    val permissionsToRequest = remember {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(context, "Wymagane uprawnienia nie zostały w pełni przyznane", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val missing = permissionsToRequest.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    LaunchedEffect(userMessage) {
        userMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearUserMessage()
        }
    }

    // Dialog wyboru lokalizacji na mapie
    slotForMapDialog?.let { slotToEdit ->
        MapPickerDialog(
            slot = slotToEdit,
            onDismiss = { slotForMapDialog = null },
            onConfirm = { newLat, newLon, newRadius ->
                val updated = slotToEdit.copy(
                    latitude = newLat,
                    longitude = newLon,
                    radiusMeters = newRadius
                )
                viewModel.updateSlot(updated)
                slotForMapDialog = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("AutoBrama", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // KARTA 1: Główny przełącznik automatyzacji i sprawdzania Android Auto
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAutomationEnabled) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Główny przełącznik automatyzacji
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automatyzacja otwierania bram",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isAutomationEnabled) Color(0xFF2E7D32) else Color(0xFF616161)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isAutomationEnabled)
                                    "Aktywna"
                                else
                                    "Wyłączona – oszczędzanie baterii",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                        }
                        Switch(
                            checked = isAutomationEnabled,
                            onCheckedChange = { isChecked ->
                                viewModel.toggleAutomation(isChecked, context)
                            }
                        )
                    }

                    HorizontalDivider()

                    // Przełącznik sprawdzania Android Auto (tryb testowy bez auta)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wymagaj Android Auto",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (requireAndroidAuto)
                                    "GPS włącza się tylko w aucie (oszczędza baterię)"
                                else
                                    "Tryb testowy (bez auta) – GPS aktywny od razu",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!requireAndroidAuto) Color(0xFFC62828) else Color.DarkGray
                            )
                        }
                        Switch(
                            checked = requireAndroidAuto,
                            onCheckedChange = { isChecked ->
                                viewModel.toggleRequireAndroidAuto(isChecked, context)
                            }
                        )
                    }
                }
            }

            // KARTA 2: Monitor stanu na żywo
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Status w czasie rzeczywistym",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    HorizontalDivider()

                    // Stan Android Auto
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Android Auto:", style = MaterialTheme.typography.bodyMedium)
                        val isAAConnected = carConnectionType == CarConnection.CONNECTION_TYPE_PROJECTION
                        if (!requireAndroidAuto) {
                            BadgeBox(
                                text = "NIEWYMAGANE (Bez auta)",
                                backgroundColor = Color(0xFF00897B)
                            )
                        } else {
                            BadgeBox(
                                text = if (isAAConnected) "POŁĄCZONO (Projekcja)" else "ROZŁĄCZONO",
                                backgroundColor = if (isAAConnected) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                            )
                        }
                    }

                    // Stan Foreground Service
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Foreground Service:", style = MaterialTheme.typography.bodyMedium)
                        BadgeBox(
                            text = if (isServiceRunning) "DZIAŁA (GPS 3s)" else "ZATRZYMANY",
                            backgroundColor = if (isServiceRunning) Color(0xFF2196F3) else Color(0xFF9E9E9E)
                        )
                    }

                    // Najbliższa aktywna brama
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Najbliższa brama:", style = MaterialTheme.typography.bodyMedium)
                        if (closestGateStatus != null) {
                            val c = closestGateStatus!!
                            Text(
                                text = "${c.slot.name} (${c.distanceMeters.toInt()} m)",
                                fontWeight = FontWeight.Bold,
                                color = if (c.distanceMeters <= c.slot.radiusMeters) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                text = if (isServiceRunning) "Brak aktywnych stref" else "Serwis zatrzymany",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }

                    // Maszyna stanów najbliższej bramy
                    closestGateStatus?.let { c ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Stan strefy (${c.slot.name}):", style = MaterialTheme.typography.bodyMedium)
                            val stateLabel = when (c.zoneState) {
                                GateZoneState.OUTSIDE_ZONE -> "Czuwanie (poza strefą)"
                                GateZoneState.IN_ZONE_TRIGGERED -> "W strefie (połączenie zrobione)"
                                GateZoneState.HYSTERESIS_COOLDOWN -> "Histereza (+150m blokada)"
                            }
                            Text(
                                text = stateLabel,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = when (c.zoneState) {
                                    GateZoneState.OUTSIDE_ZONE -> Color(0xFF1976D2)
                                    GateZoneState.IN_ZONE_TRIGGERED -> Color(0xFF388E3C)
                                    GateZoneState.HYSTERESIS_COOLDOWN -> Color(0xFFE65100)
                                }
                            )
                        }
                    }

                    // Ostatnie połączenie
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Ostatnie połączenie:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (lastCallTimestamp > 0) {
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastCallTimestamp))
                            } else {
                                "Nigdy"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )
                    }
                }
            }

            // KARTA 3: Konfiguracja 3 niezależnych slotów bram
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Konfiguracja lokalizacji bram (3 sloty)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Zakładki TabRow dla slotów 1, 2, 3
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        slots.forEachIndexed { index, slot ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(
                                                    if (slot.enabled) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                                                    CircleShape
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Slot ${slot.id}",
                                            fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            )
                        }
                    }

                    // Formularz dla aktualnie wybranego slotu
                    val currentSlot = slots.getOrNull(selectedTabIndex) ?: GateLocationSlot.defaultSlots()[0]
                    SlotConfigurationForm(
                        slot = currentSlot,
                        onUpdateSlot = { updated -> viewModel.updateSlot(updated) },
                        onOpenMap = { slotForMapDialog = currentSlot },
                        onTestCall = { phone -> viewModel.testMakeCall(phone, context) }
                    )
                }
            }

            // KARTA 4: Diagnostyka i symulator dla testów
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Diagnostyka i symulacja Android Auto",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    Text(
                        text = "Narzędzia pozwalające przetestować działanie BroadcastReceivera i serwisu w emulatorze:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.simulateAndroidAutoConnection(context, true) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text("Symuluj AA ON", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.simulateAndroidAutoConnection(context, false) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                        ) {
                            Text("Symuluj AA OFF", fontSize = 11.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.toggleServiceManually(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isServiceRunning) "Ręcznie zatrzymaj serwis" else "Ręcznie uruchom serwis")
                    }
                }
            }
        }
    }
}

@Composable
fun SlotConfigurationForm(
    slot: GateLocationSlot,
    onUpdateSlot: (GateLocationSlot) -> Unit,
    onOpenMap: () -> Unit,
    onTestCall: (String) -> Unit
) {
    var nameInput by remember(slot.id, slot.name) { mutableStateOf(slot.name) }
    var phoneInput by remember(slot.id, slot.phoneNumber) { mutableStateOf(slot.phoneNumber) }
    var radiusInput by remember(slot.id, slot.radiusMeters) { mutableFloatStateOf(slot.radiusMeters) }
    var isEnabled by remember(slot.id, slot.enabled) { mutableStateOf(slot.enabled) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Przełącznik aktywności slotu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isEnabled) Color(0xFFE8F5E9) else Color(0xFFEEEEEE), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEnabled) "Ten slot jest aktywny" else "Ten slot jest wyłączony",
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = if (isEnabled) Color(0xFF2E7D32) else Color.Gray
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = { checked ->
                    isEnabled = checked
                    onUpdateSlot(slot.copy(enabled = checked))
                }
            )
        }

        // Nazwa bramy
        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("Nazwa lokalizacji") },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) }
        )

        // Numer telefonu bramy
        OutlinedTextField(
            value = phoneInput,
            onValueChange = { phoneInput = it },
            label = { Text("Numer telefonu do bramy") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { onTestCall(phoneInput) }) {
                    Icon(Icons.Default.Call, contentDescription = "Zadzwoń testowo", tint = Color(0xFF388E3C))
                }
            }
        )

        // Współrzędne i przycisk mapy
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Współrzędne: %.5f, %.5f".format(slot.latitude, slot.longitude),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Button(
                    onClick = onOpenMap,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Wybierz punkt na mapie (OSM)")
                }
            }
        }

        // Suwak promienia strefy
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Dystans aktywacji (promień):", fontSize = 12.sp)
                Text("${radiusInput.toInt()} m", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = radiusInput,
                onValueChange = { radiusInput = it },
                valueRange = 20f..500f,
                steps = 47,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Próg histerezy wyjścia: ${(radiusInput + 150).toInt()} m (r + 150m)",
                fontSize = 11.sp,
                color = Color.DarkGray
            )
        }

        // Przycisk Zapisz zmiany
        Button(
            onClick = {
                val updated = slot.copy(
                    name = nameInput,
                    phoneNumber = phoneInput,
                    radiusMeters = radiusInput,
                    enabled = isEnabled
                )
                onUpdateSlot(updated)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Zapisz ustawienia dla Slot ${slot.id}")
        }
    }
}

@Composable
fun BadgeBox(text: String, backgroundColor: Color) {
    Box(
        modifier = Modifier
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
