package com.autobrama.app.ui

import android.Manifest
import android.os.Bundle
import android.telecom.TelecomManager
import java.io.ByteArrayOutputStream
import com.autobrama.app.data.SettingsBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.car.app.connection.CarConnection
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.autobrama.app.connection.CarConnectionManager
import com.autobrama.app.connection.RadarBleManager
import com.autobrama.app.connection.NearbyBleDevice
import com.autobrama.app.data.RadarConfig
import com.autobrama.app.data.GateLocationSlot
import com.autobrama.app.data.PreferencesManager
import com.autobrama.app.receiver.CarConnectionReceiver
import com.autobrama.app.service.ActiveGateStatus
import com.autobrama.app.service.AutoCallForegroundService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private var recoveryInProgress = false

    val isAutomationEnabled: StateFlow<Boolean> = prefs.isAutomationEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val requireAndroidAuto: StateFlow<Boolean> = prefs.requireAndroidAutoFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val radarConfig: StateFlow<RadarConfig> = prefs.radarConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RadarConfig(false, "", ""))
    val radarPresent = RadarBleManager.present
    val radarScanStatus = RadarBleManager.status
    val radarDevices = RadarBleManager.devices

    val slots: StateFlow<List<GateLocationSlot>> = prefs.slotsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GateLocationSlot.defaultSlots())

    val lastCallTimestamp: StateFlow<Long> = prefs.lastCallTimestampFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val isServiceRunning: StateFlow<Boolean> = AutoCallForegroundService.isRunning
    val isGpsActive: StateFlow<Boolean> = AutoCallForegroundService.isGpsActive
    val isRearmMonitoring: StateFlow<Boolean> = AutoCallForegroundService.isRearmMonitoring
    val closestGateStatus: StateFlow<ActiveGateStatus?> = AutoCallForegroundService.closestGateStatus
    val gateStatuses: StateFlow<Map<Int, ActiveGateStatus>> = AutoCallForegroundService.gateStatuses
    val serviceStatusMessage: StateFlow<String> = AutoCallForegroundService.statusMessage

    val carConnectionType: StateFlow<Int> = CarConnectionManager.connectionType

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _pendingImport = MutableStateFlow<SettingsBackup?>(null)
    val pendingImport: StateFlow<SettingsBackup?> = _pendingImport.asStateFlow()

    init {
        CarConnectionManager.init(application)
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    /**
     * Reconcile the persisted automation switch with the real service state.
     * Called only from visible UI / permission result, never from Application
     * startup or a background worker (Android 12+ forbids many FGS starts there).
     */
    fun recoverMonitoringFromVisibleUi(context: Context) {
        if (recoveryInProgress || isServiceRunning.value) return
        recoveryInProgress = true
        viewModelScope.launch {
            try {
                val enabled = prefs.isAutomationEnabled()
                if (!enabled || isServiceRunning.value) return@launch
                val stillVisible = (context as? LifecycleOwner)
                    ?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: false
                if (!stillVisible) return@launch
                if (!AutoCallForegroundService.startService(context)) {
                    _userMessage.value =
                        "Automatyzacja jest włączona, ale serwis nie wystartował. " +
                        "Sprawdź uprawnienia do dokładnej lokalizacji."
                }
            } catch (e: Exception) {
                _userMessage.value =
                    "Nie udało się wznowić monitorowania: ${e.message ?: "błąd systemu"}"
            } finally {
                recoveryInProgress = false
            }
        }
    }

fun toggleAutomation(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            prefs.setAutomationEnabled(enabled)
            if (enabled) {
                // Start a lightweight standby FGS from the visible UI. GPS runs
                // only while Android Auto or the selected BT device is active.
                val accepted = AutoCallForegroundService.startService(context)
                if (!accepted) {
                    prefs.setAutomationEnabled(false)
                    _userMessage.value = "Nie uruchomiono usługi. Sprawdź dokładną lokalizację i uprawnienia."
                } else {
                    _userMessage.value = "Automatyzacja włączona."
                }
            } else {
                AutoCallForegroundService.stopService(context)
                _userMessage.value = "Automatyzacja wyłączona. Serwis zatrzymany."
            }
        }
    }

fun toggleRequireAndroidAuto(required: Boolean, context: Context) {
        viewModelScope.launch {
            prefs.setAndroidAutoRequired(required)
            if (prefs.isAutomationEnabled() && !isServiceRunning.value) {
                AutoCallForegroundService.startService(context)
            }
            _userMessage.value = if (required)
                "GPS włączy się przy połączeniu z Android Auto."
            else "Android Auto nie jest wymagane."
        }
    }

    fun beginRadarDiscovery(context: Context) {
        if (!RadarBleManager.hasPermission(context)) {
            _userMessage.value = "Przyznaj dostęp do pobliskich urządzeń Bluetooth oraz lokalizacji."
            return
        }
        RadarBleManager.startDiscovery(context)
        _userMessage.value = "Skanowanie BLE przez 30 sekund. Włącz urządzenie, które chcesz wybrać."
    }

    fun selectRadar(device: NearbyBleDevice, context: Context) {
        viewModelScope.launch {
            prefs.setRadarConfig(true, device.address, device.name, device.signature)
            recoverMonitoringFromVisibleUi(context)
            // End discovery at once so the filtered monitoring scan starts and the
            // status line shows straight away whether the selected device is heard.
            RadarBleManager.stopDiscovery()
            _userMessage.value = "Wybrano ${device.name}. Status poniżej pokazuje, czy nasłuch je odbiera."
        }
    }

    fun toggleRadarEnabled(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            val previous = prefs.radarConfigFlow.first()
            if (enabled && previous.address.isBlank()) {
                _userMessage.value = "Najpierw wyszukaj i wybierz urządzenie BT."
                return@launch
            }
            prefs.setRadarConfig(enabled, previous.address, previous.name, previous.signature)
            // The BT switch must not leave a persisted-on automation with a dead
            // standby service. Start it from the currently visible activity.
            if (enabled) recoverMonitoringFromVisibleUi(context)
            _userMessage.value = if (enabled)
                "GPS włączy się po wykryciu wybranego urządzenia BT."
            else "Urządzenie BT nie jest wymagane."
        }
    }

    fun updateSlot(slot: GateLocationSlot) {
        viewModelScope.launch {
            prefs.updateSlot(slot)
            _userMessage.value = "Zaktualizowano: ${slot.name}"
        }
    }

    fun toggleSlotEnabled(slotId: Int, enabled: Boolean) {
        viewModelScope.launch {
            val current = prefs.getSlots().find { it.id == slotId } ?: return@launch
            prefs.updateSlot(current.copy(enabled = enabled))
        }
    }

fun testMakeCall(phoneNumber: String, context: Context) {
        if (phoneNumber.isBlank()) {
            _userMessage.value = "Numer bramy jest pusty!"
            return
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            _userMessage.value = "Brak uprawnienia CALL_PHONE!"
            return
        }
        try {
            val manager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            manager.placeCall(Uri.fromParts("tel", phoneNumber, null), Bundle())
            _userMessage.value = "Wysłano testowe żądanie połączenia."
        } catch (e: Exception) {
            _userMessage.value = "Błąd wybierania numeru: ${e.message}"
        }
    }

    fun toggleServiceManually(context: Context) {
        if (isServiceRunning.value) {
            AutoCallForegroundService.stopService(context)
            _userMessage.value = "Serwis zatrzymany ręcznie."
        } else {
            AutoCallForegroundService.startService(context)
            _userMessage.value = "Serwis uruchomiony ręcznie."
        }
    }

    fun exportSettingsToUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val text = prefs.exportSettingsJson()
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("Nie można otworzyć pliku do zapisu.")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                _userMessage.value = "Ustawienia zapisane do pliku JSON."
            } catch (e: Exception) {
                _userMessage.value = "Eksport nieudany: ${e.message}"
            }
        }
    }

    fun previewImportFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Nie można odczytać pliku.")
                    input.use { stream ->
                        val buffer = ByteArray(4096)
                        val output = ByteArrayOutputStream()
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= SettingsBackup.MAX_BACKUP_BYTES) {
                                "Plik konfiguracji jest zbyt duży."
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                }
                _pendingImport.value = SettingsBackup.parse(json)
            } catch (e: Exception) {
                _pendingImport.value = null
                _userMessage.value = "Nie udało się zaimportować: ${e.message}"
            }
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    fun confirmImport(context: Context) {
        val backup = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            try {
                // Stop existing tracking before changing all 3 gates.
                AutoCallForegroundService.stopService(context)
                prefs.importSettings(backup)
                val started = if (backup.automationEnabled)
                    AutoCallForegroundService.startService(context)
                else true
                _userMessage.value = if (started) "Zaimportowano bramy, radar BLE i automatyzację."
                else "Ustawienia zaimportowane, ale usługa nie wystartowała. Sprawdź GPS."
            } catch (e: Exception) {
                _userMessage.value = "Import nieudany: ${e.message}"
            }
        }
    }

        fun simulateAndroidAutoConnection(context: Context, connected: Boolean) {
        val type = if (connected) CarConnection.CONNECTION_TYPE_PROJECTION
            else CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        // Developer-only control, not an exported broadcast.
        CarConnectionManager.updateState(type, simulated = true)
        _userMessage.value = if (connected) "Symulacja: Android Auto połączone"
            else "Symulacja: Android Auto rozłączone"
    }

}
