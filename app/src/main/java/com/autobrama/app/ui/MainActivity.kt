package com.autobrama.app.ui

import android.Manifest
import com.autobrama.app.connection.RadarBleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.autobrama.app.BuildConfig
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlin.math.roundToInt

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
    val radarConfig by viewModel.radarConfig.collectAsState()
    val radarPresent by viewModel.radarPresent.collectAsState()
    val radarDevices by viewModel.radarDevices.collectAsState()
    val radarScanStatus by viewModel.radarScanStatus.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val lastCallTimestamp by viewModel.lastCallTimestamp.collectAsState()

    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val isGpsActive by viewModel.isGpsActive.collectAsState()
    val serviceStatusMessage by viewModel.serviceStatusMessage.collectAsState()
    val pendingImport by viewModel.pendingImport.collectAsState()
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

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it } && RadarBleManager.hasPermission(context)) {
            viewModel.beginRadarDiscovery(context)
        } else {
            Toast.makeText(context,
                "Zezwól na pobliskie urządzenia Bluetooth i dokładną lokalizację",
                Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(userMessage) {
        userMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearUserMessage()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportSettingsToUri(context, it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.previewImportFromUri(context, it) } }

    pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Zastąpić ustawienia?") },
            text = {
                Text(
                    "Import zastąpi konfigurację wszystkich 3 bram (lokalizacje, numery, odległości) " +
                    "oraz ustawienia automatyzacji, Android Auto i radaru BLE.\\n\\n" +
                    "Automatyzacja po imporcie: " +
                    (if (backup.automationEnabled) "WŁĄCZONA" else "WYŁĄCZONA") +
                    ". Potwierdzasz?"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport(context) }) { Text("Importuj") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) { Text("Anuluj") }
            }
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var hasBackgroundLocation by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasBackgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Dialog wyboru lokalizacji na mapie
    slotForMapDialog?.let { slotToEdit ->
        MapPickerDialog(
            slot = slotToEdit,
            onDismiss = { slotForMapDialog = null },
            onConfirm = { newLat, newLon, newRadius, newHysteresis ->
                val updated = slotToEdit.copy(
                    latitude = newLat,
                    longitude = newLon,
                    radiusMeters = newRadius,
                    hysteresisMeters = newHysteresis
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
                                    if (radarConfig.enabled) "GPS uruchamia Android Auto lub radar Magene"
                                    else "GPS włącza się tylko w aucie (oszczędza baterię)"
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

            // RADAR BLE: wybór rzeczywistego urządzenia oraz czujnik obecności.
            Card(modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Magene L508 — wykrywanie BLE",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Radar aktywuje GPS również bez Android Auto",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = radarConfig.enabled,
                            enabled = radarConfig.address.isNotBlank(),
                            onCheckedChange = { checked ->
                                viewModel.toggleRadarEnabled(checked)
                            }
                        )
                    }
                    Text(
                        text = if (radarConfig.address.isBlank())
                            "Brak wybranego radaru. Włącz L508 i wybierz go z listy."
                        else "Wybrano: ${radarConfig.name} (${radarConfig.address})",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = if (radarPresent) "RADAR W POBLIŻU — sygnał BLE potwierdzony"
                            else if (radarConfig.enabled) "Radar nie został wykryty"
                            else "Monitorowanie radaru wyłączone",
                        color = if (radarPresent) Color(0xFF2E7D32) else Color.Gray,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(radarScanStatus, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            if (RadarBleManager.hasPermission(context)) {
                                viewModel.beginRadarDiscovery(context)
                            } else {
                                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    )
                                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                blePermissionLauncher.launch(permissions)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Wyszukaj urządzenia BLE (30 s)") }
                    if (radarDevices.isNotEmpty()) {
                        Text(
                            "Wybierz swój radar (np. 19813-5 / L508):",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        radarDevices.take(12).forEach { device ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { viewModel.selectRadar(device) }
                            ) {
                                Text("${device.name}  ·  ${device.address}  ·  ${device.rssi} dBm")
                            }
                        }
                    }
                    Text(
                        "Obecność BLE nie potwierdza jazdy rowerem. Skanowanie nie " +
                        "łączy się z radarem. Przy zmianie adresu BLE może być wymagany ponowny wybór.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                            text = if (isGpsActive) "GPS AKTYWNY" else if (isServiceRunning) "CZUWA (GPS OFF)" else "ZATRZYMANY",
                            backgroundColor = if (isGpsActive) Color(0xFF2196F3) else if (isServiceRunning) Color(0xFF00897B) else Color(0xFF9E9E9E)
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
                                GateZoneState.IN_ZONE_TRIGGERED -> "Wyzwolenie obsłużone (blokada)"
                                GateZoneState.HYSTERESIS_COOLDOWN -> "Histereza (+${c.slot.hysteresisMeters.toInt()} m blokada)"
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

                    Text(
                        text = serviceStatusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

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

            // KARTA 4: Kopia zapasowa wszystkich ustawień
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Eksport / import ustawień",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Zapisz lub odtwórz 3 bramy, numery telefonów, współrzędne GPS, " +
                        "odległości aktywacji, aktywne sloty, tryb Android Auto i radar BLE. " +
                        "Plik JSON zawiera prywatne dane – nie udostępniaj go publicznie.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { exportLauncher.launch("AndroidBrama-ustawienia.json") }
                        ) { Text("Eksport JSON") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { importLauncher.launch(arrayOf("*/*")) }
                        ) { Text("Import JSON") }
                    }
                }
            }

            // Dla odzyskiwania usługi z tła Android wymaga osobnego uprawnienia.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Lokalizacja w tle",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Aby umożliwić monitorowanie po zablokowaniu ekranu i poprawić " +
                            "odzyskiwanie usługi po jej zamknięciu, otwórz uprawnienia aplikacji " +
                            "i ustaw Lokalizacja → Zezwalaj zawsze (oraz Dokładna lokalizacja). " +
                            "Android może nadal ograniczać uruchamianie usług z tła.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        }) { Text("Otwórz uprawnienia aplikacji") }
                    }
                }
            }

            if (BuildConfig.DEBUG) {
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
            } // Tylko kompilacja debug: symulacja Android Auto
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
    var radiusText by remember(slot.id, slot.radiusMeters) {
        mutableStateOf(slot.radiusMeters.roundToInt().toString())
    }
    var hysteresisText by remember(slot.id, slot.hysteresisMeters) {
        mutableStateOf(slot.hysteresisMeters.roundToInt().toString())
    }
    val radiusValue = radiusText.toIntOrNull()
    val hysteresisValue = hysteresisText.toIntOrNull()
    val radiusValid = radiusValue != null && radiusValue in 20..500
    val hysteresisValid = hysteresisValue != null && hysteresisValue in 0..2000
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

        // Ręczny wpis co 1 metr, suwak pozostaje ułatwieniem.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = radiusText,
                onValueChange = { value ->
                    if (value.length <= 4 && value.all { it.isDigit() }) radiusText = value
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Dystans aktywacji (promień)") },
                suffix = { Text("m") },
                singleLine = true,
                isError = !radiusValid,
                supportingText = { Text("Zakres 20–500 m, wpisz dokładną liczbę metrów") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Slider(
                value = (radiusValue?.toFloat() ?: slot.radiusMeters).coerceIn(20f, 500f),
                onValueChange = { radiusText = it.roundToInt().toString() },
                valueRange = 20f..500f,
                steps = 47,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = hysteresisText,
                onValueChange = { value ->
                    if (value.length <= 4 && value.all { it.isDigit() }) hysteresisText = value
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Histereza dodatkowa") },
                suffix = { Text("m") },
                singleLine = true,
                isError = !hysteresisValid,
                supportingText = { Text("Zakres 0–2000 m; domyślnie 150 m") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                text = if (radiusValid && hysteresisValid)
                    "Ponowne uzbrojenie po oddaleniu na co najmniej ${radiusValue!! + hysteresisValue!!} m (promień + histereza)"
                else "Wprowadź poprawne odległości, aby zapisać ustawienia.",
                fontSize = 12.sp,
                color = if (radiusValid && hysteresisValid) Color.DarkGray
                    else MaterialTheme.colorScheme.error
            )
        }

        // Przycisk Zapisz zmiany
        Button(
            onClick = {
                val updated = slot.copy(
                    name = nameInput,
                    phoneNumber = phoneInput,
                    radiusMeters = radiusValue!!.toFloat(),
                    hysteresisMeters = hysteresisValue!!.toFloat(),
                    enabled = isEnabled
                )
                onUpdateSlot(updated)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = radiusValid && hysteresisValid,
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
