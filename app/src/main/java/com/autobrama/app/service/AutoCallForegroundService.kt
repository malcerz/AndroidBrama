package com.autobrama.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.autobrama.app.AutoBramaApplication
import com.autobrama.app.R
import com.autobrama.app.connection.CarConnectionManager
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
import kotlinx.coroutines.launch

data class ActiveGateStatus(
    val slot: GateLocationSlot,
    val distanceMeters: Float,
    val zoneState: GateZoneState
)

class AutoCallForegroundService : Service() {

    companion object {
        private const val TAG = "AutoCallFgService"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _closestGateStatus = MutableStateFlow<ActiveGateStatus?>(null)
        val closestGateStatus: StateFlow<ActiveGateStatus?> = _closestGateStatus.asStateFlow()

        private val _gateStatuses = MutableStateFlow<Map<Int, ActiveGateStatus>>(emptyMap())
        val gateStatuses: StateFlow<Map<Int, ActiveGateStatus>> = _gateStatuses.asStateFlow()

        private val _statusMessage = MutableStateFlow("Inicjalizacja usługi...")
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, AutoCallForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AutoCallForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Niezależne maszyny stanów dla każdego z 3 slotów
    private val stateMachines = mutableMapOf<Int, GateTriggerStateMachine>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Tworzenie usługi AutoCallForegroundService (3 sloty)")
        preferencesManager = PreferencesManager(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        _isRunning.value = true
        stateMachines.clear()

        startAsForeground("Połączono z Android Auto – monitorowanie stref...")

        setupCarConnectionMonitoring()
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: AutoCallForegroundService uruchomiony")
        return START_STICKY
    }

    private fun startAsForeground(contentText: String) {
        val notification = buildNotification(contentText)
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            AutoBramaApplication.NOTIFICATION_ID,
            notification,
            foregroundType
        )
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
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

    private fun updateNotification(contentText: String) {
        _statusMessage.value = contentText
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(AutoBramaApplication.NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun setupCarConnectionMonitoring() {
        serviceScope.launch {
            combine(
                CarConnectionManager.connectionType,
                preferencesManager.requireAndroidAutoFlow
            ) { connectionType, requireAA ->
                Pair(connectionType, requireAA)
            }.collect { (connectionType, requireAA) ->
                Log.d(TAG, "Stan CarConnection w usłudze: $connectionType (wymagane AA: $requireAA)")
                if (requireAA && connectionType == CarConnection.CONNECTION_TYPE_NOT_CONNECTED && _isRunning.value) {
                    Log.i(TAG, "Android Auto rozłączone i wymagane – zatrzymywanie usługi AutoCallForegroundService")
                    stopSelf()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).apply {
            setMinUpdateIntervalMillis(2000L)
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                onNewLocation(location)
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.i(TAG, "Rozpoczęto odczyt lokalizacji co 3s dla aktywnych slotów bram")
        } else {
            Log.w(TAG, "Brak uprawnień ACCESS_FINE_LOCATION!")
            updateNotification("Brak uprawnień lokalizacji!")
        }
    }

    private fun onNewLocation(location: Location) {
        serviceScope.launch {
            val allSlots = preferencesManager.getSlots()
            val activeSlots = allSlots.filter { it.enabled && it.phoneNumber.isNotBlank() }
            val requireAA = preferencesManager.isAndroidAutoRequired()
            val prefix = if (requireAA) "Połączono z Android Auto" else "AutoBrama (tryb bez auta)"

            if (activeSlots.isEmpty()) {
                updateNotification("$prefix – brak aktywnych bram")
                _closestGateStatus.value = null
                _gateStatuses.value = emptyMap()
                return@launch
            }

            val currentStatuses = mutableMapOf<Int, ActiveGateStatus>()
            var callTriggeredThisTick = false

            for (slot in activeSlots) {
                val distanceResults = FloatArray(1)
                Location.distanceBetween(
                    location.latitude,
                    location.longitude,
                    slot.latitude,
                    slot.longitude,
                    distanceResults
                )
                val distance = distanceResults[0]

                // Pobierz lub utwórz niezależną maszynę stanów dla tego slotu
                val sm = stateMachines.getOrPut(slot.id) {
                    GateTriggerStateMachine(hysteresisOffsetMeters = 150.0f)
                }

                val action = sm.processDistance(distance, slot.radiusMeters)
                currentStatuses[slot.id] = ActiveGateStatus(slot, distance, sm.currentState)

                if (action is StateMachineAction.TriggerCall) {
                    callTriggeredThisTick = true
                    Log.i(TAG, "Wjazd w strefę slotu ${slot.name}! Dystans: ${distance.toInt()} m, promień: ${slot.radiusMeters} m")
                    updateNotification("$prefix – wykonano połączenie: ${slot.name}!")
                    makeGateCall(slot.phoneNumber, slot.name)
                }
            }

            _gateStatuses.value = currentStatuses
            val closest = currentStatuses.values.minByOrNull { it.distanceMeters }
            _closestGateStatus.value = closest

            if (!callTriggeredThisTick && closest != null) {
                val notifText = "$prefix – najbliżej: ${closest.slot.name} (${closest.distanceMeters.toInt()} m)"
                updateNotification(notifText)
            }
        }
    }

    private fun makeGateCall(phoneNumber: String, gateName: String) {
        if (phoneNumber.isBlank()) {
            Log.w(TAG, "Nie można wykonać połączenia dla $gateName – pusty numer")
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Brak uprawnienia CALL_PHONE do wykonania połączenia do $gateName ($phoneNumber)")
            return
        }

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
            Log.i(TAG, "Wywołano Intent.ACTION_CALL pod numer: $phoneNumber ($gateName)")

            serviceScope.launch {
                preferencesManager.setLastCallTimestamp(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd podczas wywoływania Intent.ACTION_CALL dla $gateName", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: AutoCallForegroundService zatrzymany")
        _isRunning.value = false
        _closestGateStatus.value = null
        _gateStatuses.value = emptyMap()
        _statusMessage.value = "Usługa zatrzymana"

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Błąd podczas removeLocationUpdates", e)
        }

        stateMachines.clear()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
