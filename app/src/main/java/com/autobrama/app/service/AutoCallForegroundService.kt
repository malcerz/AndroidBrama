package com.autobrama.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.autobrama.app.AutoBramaApplication
import com.autobrama.app.R
import com.autobrama.app.connection.CarConnectionManager
import com.autobrama.app.connection.RadarBleManager
import com.autobrama.app.data.RadarConfig
import com.autobrama.app.data.GateLocationSlot
import com.autobrama.app.data.PreferencesManager
import com.autobrama.app.statemachine.GateTriggerStateMachine
import com.autobrama.app.statemachine.GateZoneState
import com.autobrama.app.statemachine.StateMachineAction
import com.autobrama.app.ui.MainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ActiveGateStatus(
    val slot: GateLocationSlot,
    val distanceMeters: Float,
    val zoneState: GateZoneState
)

/**
 * A small foreground standby service stays alive after the user enables automation.
 * It does not request GPS while Android Auto is disconnected, avoiding an illegal
 * Android 12+ background service start on the next reconnection.
 */
class AutoCallForegroundService : Service() {
    companion object {
        private const val TAG = "AutoCallFgService"
        private const val GLOBAL_CALL_COOLDOWN_MS = 30_000L
        private const val FAILED_RETRY_COOLDOWN_MS = 60_000L
        private const val MAX_FIX_AGE_NANOS = 15_000_000_000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        private val _isGpsActive = MutableStateFlow(false)
        val isGpsActive: StateFlow<Boolean> = _isGpsActive.asStateFlow()
        private val _isRearmMonitoring = MutableStateFlow(false)
        val isRearmMonitoring: StateFlow<Boolean> = _isRearmMonitoring.asStateFlow()
        private val _closestGateStatus = MutableStateFlow<ActiveGateStatus?>(null)
        val closestGateStatus: StateFlow<ActiveGateStatus?> = _closestGateStatus.asStateFlow()
        private val _gateStatuses = MutableStateFlow<Map<Int, ActiveGateStatus>>(emptyMap())
        val gateStatuses: StateFlow<Map<Int, ActiveGateStatus>> = _gateStatuses.asStateFlow()
        private val _statusMessage = MutableStateFlow("Usługa wyłączona")
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

        /** Returns whether Android accepted the start request, not whether tracking started. */
        fun startService(context: Context): Boolean {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                _statusMessage.value = "Brak dokładnej lokalizacji. Przyznaj uprawnienie GPS."
                return false
            }
            return try {
                ContextCompat.startForegroundService(
                    context, Intent(context, AutoCallForegroundService::class.java)
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "System zablokował uruchomienie usługi", e)
                _statusMessage.value =
                    "Android zablokował uruchomienie w tle. Otwórz AutoBrama i włącz automatyzację."
                false
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, AutoCallForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val locationMutex = Mutex()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var locationListening = false
    private var trackingDecision = TrackingDecision(false)
    // Only listens while a previously triggered gate remains latched AND neither
    // Android Auto nor the selected Bluetooth device permits normal tracking.
    // It can clear a latch after a verified departure but can never dial.
    private var rearmListening = false
    private var rearmCallback: LocationCallback? = null
    private val departureEvidence = DepartureEvidence()
    private val radarGrace = RadarGrace()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMachines = mutableMapOf<Int, GateTriggerStateMachine>()
    private val failedRetryAfter = mutableMapOf<Int, Long>()
    private var localLastCallAt = 0L

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        CarConnectionManager.init(applicationContext)

        try {
            startAsForeground("Uruchamianie monitorowania...")
        } catch (e: Exception) {
            Log.e(TAG, "Nie można uruchomić Foreground Service", e)
            _statusMessage.value = "Brak uprawnień do uruchomienia monitorowania."
            stopSelf()
            return
        }
        _isRunning.value = true
        observeAutomation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A restarted service must still respect the persisted automation switch.
        return START_STICKY
    }

    private fun observeAutomation() {
        serviceScope.launch {
            val options = combine(
                preferencesManager.isAutomationEnabledFlow,
                preferencesManager.requireAndroidAutoFlow,
                CarConnectionManager.connectionType,
                preferencesManager.slotsFlow,
                preferencesManager.radarConfigFlow
            ) { enabled, requireAA, carType, slots, radarConfig ->
                MonitorConfig(enabled, requireAA, carType, slots, radarConfig)
            }
            combine(
                options,
                RadarBleManager.present,
                preferencesManager.latchedGateIdsFlow
            ) { config, present, latched ->
                config.copy(radarPresent = present, latchedGateIds = latched)
            }.collect { config ->
                if (!config.enabled) {
                    trackingDecision = TrackingDecision(false)
                    stopRearmOnly()
                    RadarBleManager.setMonitoring(applicationContext, false, "", "")
                    stopLocationUpdates()
                    stopSelf()
                    return@collect
                }

                // Listen for the selected device's advertisements even when GPS
                // is sleeping; no BLE connection is established to it.
                val selected = config.radarConfig
                RadarBleManager.setMonitoring(
                    applicationContext,
                    selected.enabled,
                    selected.address,
                    selected.name,
                    selected.signature
                )

                val activeSlots = config.slots.any {
                    it.enabled && it.phoneNumber.isNotBlank()
                }
                radarGrace.update(config.radarPresent, SystemClock.elapsedRealtime())
                trackingDecision = TrackingPolicy.evaluate(
                    requireAndroidAuto = config.requireAA,
                    requireBtDevice = selected.enabled && selected.address.isNotBlank(),
                    androidAutoConnected =
                        config.carType == CarConnection.CONNECTION_TYPE_PROJECTION,
                    btDevicePresent = config.radarPresent
                )

                when {
                    !activeSlots -> {
                        stopLocationUpdates()
                        stopRearmOnly()
                        updateNotification("Brak skonfigurowanych aktywnych bram")
                    }
                    !trackingDecision.allowed -> {
                        stopLocationUpdates()
                        // Without this, a persistent gate latch can remain stuck
                        // forever if GPS sleeps during the departure from a gate.
                        val needsRearm = config.slots.any {
                            it.enabled && it.phoneNumber.isNotBlank() &&
                                it.id in config.latchedGateIds
                        }
                        if (needsRearm) startRearmOnly() else stopRearmOnly()
                        updateNotification(
                            if (rearmListening) {
                                "Czuwam: sprawdzam oddalenie od bramy (bez dzwonienia)"
                            } else if (config.requireAA && selected.enabled && selected.address.isNotBlank()) {
                                "Czekam na Android Auto lub urządzenie BT (${selected.name})"
                            } else if (selected.enabled && selected.address.isNotBlank()) {
                                "Czekam na urządzenie BT (${selected.name})"
                            } else if (config.carType == CarConnectionManager.UNKNOWN) {
                                "Ustalanie stanu Android Auto – GPS wyłączony"
                            } else {
                                "Czekam na Android Auto – GPS wyłączony"
                            }
                        )
                    }
                    else -> {
                        stopRearmOnly()
                        startLocationUpdates()
                    }
                }
            }
        }
    }

    private data class MonitorConfig(
        val enabled: Boolean,
        val requireAA: Boolean,
        val carType: Int,
        val slots: List<GateLocationSlot>,
        val radarConfig: RadarConfig,
        val radarPresent: Boolean = false,
        val latchedGateIds: Set<Int> = emptySet()
    )

    private fun startAsForeground(contentText: String) {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        ServiceCompat.startForeground(
            this, AutoBramaApplication.NOTIFICATION_ID,
            buildNotification(contentText), foregroundType
        )
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AutoBramaApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AutoBrama")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        _statusMessage.value = text
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(AutoBramaApplication.NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Nie można uaktualnić powiadomienia", e)
        }
    }

    /**
     * Low-duty-cycle positioning exists solely to observe departure from an
     * already blocked gate. It never invokes onNewLocation / placeGateCall.
     * Stop once all active latches are rearmed, or when normal tracking starts.
     */
    private fun startRearmOnly() {
        if (rearmListening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("Brak dokładnej lokalizacji – nie mogę uzbroić bramy")
            return
        }
        try {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L
            )
                .setMinUpdateIntervalMillis(30_000L)
                .setWaitForAccurateLocation(false)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    // A batch may contain old positions; the timestamp is checked
                    // in onRearmOnlyLocation before any persisted state is changed.
                    result.locations.forEach { onRearmOnlyLocation(it) }
                }
            }
            rearmCallback = callback
            rearmListening = true
            _isRearmMonitoring.value = true
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { error ->
                    Log.e(TAG, "Tryb uzbrajania: nie można uzyskać lokalizacji", error)
                    stopRearmOnly()
                    updateNotification("Nie mogę sprawdzić wyjazdu – sprawdź dostęp do lokalizacji")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Nie można uruchomić niskoenergetycznej obserwacji wyjazdu", e)
            stopRearmOnly()
            updateNotification("Nie można sprawdzić oddalenia od bramy")
        }
    }

    private fun stopRearmOnly() {
        if (rearmListening) {
            rearmCallback?.let { callback ->
                try { fusedLocationClient.removeLocationUpdates(callback) }
                catch (e: Exception) { Log.w(TAG, "Nie można zatrzymać obserwacji wyjazdu", e) }
            }
        }
        rearmCallback = null
        rearmListening = false
        _isRearmMonitoring.value = false
        departureEvidence.reset()
    }

    private fun onRearmOnlyLocation(location: Location) {
        serviceScope.launch {
            locationMutex.withLock {
                // A passive observation may ONLY clear an old latch. It can
                // never run the gate trigger state machine or dial the phone.
                if (!rearmListening || trackingDecision.allowed ||
                    !location.hasAccuracy()
                ) return@withLock
                val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                if (age < 0L || age > MAX_FIX_AGE_NANOS) return@withLock

                val oldLatches = preferencesManager.getLatchedGateIds()
                if (oldLatches.isEmpty()) return@withLock
                val slots = preferencesManager.getSlots()
                val updatedLatches = oldLatches.toMutableSet()
                val now = SystemClock.elapsedRealtime()
                val rearmedNames = mutableListOf<String>()
                for (slot in slots) {
                    if (slot.id !in oldLatches || !slot.enabled ||
                        slot.phoneNumber.isBlank()
                    ) continue
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        slot.latitude, slot.longitude, distance
                    )
                    if (departureEvidence.observe(
                            slot.id,
                            distance[0],
                            location.accuracy,
                            slot.radiusMeters + slot.hysteresisMeters,
                            now
                        )
                    ) {
                        updatedLatches.remove(slot.id)
                        // Keep the in-memory state machine consistent with the
                        // persisted latch removed by this confirmed departure.
                        stateMachines[slot.id]?.resetSession()
                        rearmedNames.add(slot.name)
                    }
                }
                if (updatedLatches != oldLatches) {
                    preferencesManager.setLatchedGateIds(updatedLatches)
                    Log.i(TAG, "Potwierdzono wyjazd: ponownie uzbrojone bramy: ${rearmedNames.joinToString()}")
                    updateNotification("Ponownie uzbrojono: ${rearmedNames.joinToString()}")
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (locationListening) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("Brak dokładnej lokalizacji – GPS wyłączony")
            return
        }
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(2000L)
                .setWaitForAccurateLocation(false)
                .build()
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { onNewLocation(it) }
                }
            }
            fusedLocationClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper()
            )
            locationListening = true
            _isGpsActive.value = true
            updateNotification("Lokalizacja aktywna – monitoruję strefy bram")
        } catch (e: Exception) {
            Log.e(TAG, "Nie można uruchomić lokalizacji", e)
            _isGpsActive.value = false
            updateNotification("Nie można uruchomić GPS – sprawdź lokalizację i uprawnienia")
        }
    }

    private fun stopLocationUpdates() {
        if (locationListening) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Nie udało się zatrzymać lokalizacji", e)
            }
        }
        locationListening = false
        _isGpsActive.value = false
        _closestGateStatus.value = null
        _gateStatuses.value = emptyMap()
    }

    private fun onNewLocation(location: Location) {
        serviceScope.launch {
            locationMutex.withLock {
                if (!locationListening || !trackingDecision.allowed ||
                    !location.hasAccuracy()
                ) return@withLock
                val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                if (ageNanos < 0 || ageNanos > MAX_FIX_AGE_NANOS) {
                    updateNotification("Nieaktualna pozycja GPS – czekam na nową")
                    return@withLock
                }

                val activeSlots = preferencesManager.getSlots().filter {
                    it.enabled && it.phoneNumber.isNotBlank()
                }
                val activeIds = activeSlots.map { it.id }.toSet()
                stateMachines.keys.retainAll(activeIds)
                failedRetryAfter.keys.retainAll(activeIds)
                // Reload on every GPS fix so a process restart retains the
                // previously triggered gate's hysteresis latch.
                val savedLatches = preferencesManager.getLatchedGateIds()
                val nextLatches = savedLatches.toMutableSet()
                if (activeSlots.isEmpty()) {
                    updateNotification("Brak aktywnych bram")
                    _closestGateStatus.value = null
                    _gateStatuses.value = emptyMap()
                    return@withLock
                }

                val statuses = mutableMapOf<Int, ActiveGateStatus>()
                val triggerCandidates = mutableListOf<Pair<GateLocationSlot, Float>>()
                var poorAccuracy = false
                for (slot in activeSlots) {
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        slot.latitude, slot.longitude, distance
                    )
                    val sm = stateMachines.getOrPut(slot.id) {
                        GateTriggerStateMachine(hysteresisOffsetMeters = slot.hysteresisMeters).apply {
                            if (slot.id in savedLatches) restoreTriggeredState()
                        }
                    }
                    // Apply any UI/import edits during live monitoring.
                    sm.setHysteresisMeters(slot.hysteresisMeters)
                    // Never advance zone state on a coarse or uncertain fix.
                    val maxAccuracy = minOf(50f, maxOf(15f, slot.radiusMeters / 2f))
                    // A coarse fix far outside a gate is safe for arming,
                    // while entry into the activation zone needs precise GPS.
                    val definitelyOutside =
                        distance[0] - location.accuracy >= slot.radiusMeters + slot.hysteresisMeters
                    if (location.accuracy <= maxAccuracy ||
                        (location.accuracy <= 100f && definitelyOutside)
                    ) {
                        if (sm.processDistance(distance[0], slot.radiusMeters)
                            is StateMachineAction.TriggerCall
                        ) {
                            triggerCandidates.add(slot to distance[0])
                        }
                    } else {
                        poorAccuracy = true
                    }
                    // Only a validated departure beyond radius + configured hysteresis
                    // re-arms a gate that was triggered before restart.
                    if (sm.currentState == GateZoneState.OUTSIDE_ZONE &&
                        slot.id in nextLatches
                    ) {
                        nextLatches.remove(slot.id)
                    }
                    statuses[slot.id] = ActiveGateStatus(slot, distance[0], sm.currentState)
                }
                _gateStatuses.value = statuses
                val closest = statuses.values.minByOrNull { it.distanceMeters }
                _closestGateStatus.value = closest

                // One GPS update can trigger at most one GSM request.
                // Treat other overlapping zones as triggered too, avoiding
                // a second call after service restarts.
                // Each gate has its own grace period after the selected BT device
                // appears. A zone entered during it is latched as handled, so the
                // gate re-arms only after leaving radius + hysteresis.
                val nowElapsed = SystemClock.elapsedRealtime()
                fun graceLeftFor(slot: GateLocationSlot): Long =
                    if (trackingDecision.cycling)
                        radarGrace.remaining(nowElapsed, slot.graceMinutes * 60_000L)
                    else 0L
                val held = triggerCandidates.filter { graceLeftFor(it.first) > 0L }
                val readyCandidates = triggerCandidates - held.toSet()
                val selected = readyCandidates.minByOrNull { it.second }
                if (held.isNotEmpty()) {
                    nextLatches.addAll(held.map { it.first.id })
                }
                if (selected == null && held.isNotEmpty()) {
                    val first = held.minByOrNull { it.second }!!.first
                    Log.i(TAG, "Karencja: pominięto ${first.name}")
                    updateNotification(
                        "Karencja (${graceMinutes(graceLeftFor(first))} min): " +
                            "${first.name} nie zostanie otwarta, dopóki nie odjedziesz"
                    )
                } else if (selected != null) {
                    val (slot, distance) = selected
                    val now = System.currentTimeMillis()
                    val lastCall = maxOf(
                        localLastCallAt,
                        preferencesManager.lastCallTimestampFlow.first()
                    )
                    val retryAfter = failedRetryAfter[slot.id] ?: 0L
                    val globalCooldown =
                        lastCall > 0 && now - lastCall in 0 until GLOBAL_CALL_COOLDOWN_MS
                    val overlappingIds = readyCandidates.map { it.first.id }.toSet()

                    if (now < retryAfter) {
                        // A rejected request must be retried, not latched
                        // permanently. Keep other overlapping gates blocked.
                        nextLatches.addAll(overlappingIds - slot.id)
                        stateMachines[slot.id]?.rearmAfterFailedCall()
                        updateNotification("Oczekiwanie na ponowną próbę połączenia: ${slot.name}")
                    } else if (globalCooldown) {
                        nextLatches.addAll(overlappingIds)
                        Log.i(TAG, "Blokada kolejnego połączenia przez 30 s: ${slot.name}")
                        updateNotification("Blokada 30 s po innym wyzwoleniu: ${slot.name}")
                    } else {
                        // Persist the latch BEFORE submitting a call: Android
                        // killing our process must not dial the same gate twice.
                        nextLatches.addAll(overlappingIds)
                        if (nextLatches != savedLatches) {
                            preferencesManager.setLatchedGateIds(nextLatches)
                        }

                        Log.i(TAG, "Próba połączenia: ${slot.name}, dystans ${distance.toInt()} m, próg ${slot.radiusMeters.toInt()} m")
                        if (placeGateCall(slot.phoneNumber, slot.name)) {
                            localLastCallAt = now
                            failedRetryAfter.remove(slot.id)
                            preferencesManager.setLastCallTimestamp(now)
                            updateNotification("Wysłano żądanie połączenia: ${slot.name} (${distance.toInt()} m)")
                        } else {
                            failedRetryAfter[slot.id] = now + FAILED_RETRY_COOLDOWN_MS
                            stateMachines[slot.id]?.rearmAfterFailedCall()
                            nextLatches.remove(slot.id)
                            preferencesManager.setLatchedGateIds(nextLatches)
                            updateNotification("Nie udało się zadzwonić: ${slot.name}; kolejna próba za 60 s")
                        }
                    }
                } else if (closest != null) {
                    val status = when {
                        poorAccuracy -> "GPS niedokładny – czekam na lepszy sygnał"
                        closest.zoneState == GateZoneState.HYSTERESIS_COOLDOWN ->
                            "Blokada ponownego połączenia: wyjdź poza ${(closest.slot.radiusMeters + closest.slot.hysteresisMeters).toInt()} m"
                        closest.zoneState == GateZoneState.IN_ZONE_TRIGGERED ->
                            "Brama ${closest.slot.name}: wyzwolenie już obsłużone"
                        else -> "Najbliżej: ${closest.slot.name} (${closest.distanceMeters.toInt()} m)"
                    }
                    val closestGrace = graceLeftFor(closest.slot)
                    updateNotification(
                        if (closestGrace > 0L) "$status · karencja ${graceMinutes(closestGrace)} min"
                        else status
                    )
                }

                if (nextLatches != savedLatches) {
                    preferencesManager.setLatchedGateIds(nextLatches)
                }
            }
        }
    }

    private fun graceMinutes(millis: Long): Long = (millis + 59_999L) / 60_000L

    /** true = request sent to Telecom; it does not prove that the call connected. */
    private fun placeGateCall(phoneNumber: String, gateName: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Brak CALL_PHONE dla bramy $gateName")
            return false
        }
        return try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", phoneNumber, null), Bundle())
            Log.i(TAG, "Żądanie połączenia przekazane do Telecom: $gateName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Telecom odrzucił żądanie połączenia: $gateName", e)
            false
        }
    }

    override fun onDestroy() {
        stopRearmOnly()
        RadarBleManager.setMonitoring(applicationContext, false, "", "")
        stopLocationUpdates()
        stateMachines.clear()
        failedRetryAfter.clear()
        _isRunning.value = false
        _statusMessage.value = "Usługa zatrzymana"
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
