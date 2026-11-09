package com.autobrama.app.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.car.app.connection.CarConnection
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autobrama.app.connection.CarConnectionManager
import com.autobrama.app.data.GateLocationSlot
import com.autobrama.app.data.PreferencesManager
import com.autobrama.app.receiver.CarConnectionReceiver
import com.autobrama.app.service.ActiveGateStatus
import com.autobrama.app.service.AutoCallForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)

    val isAutomationEnabled: StateFlow<Boolean> = prefs.isAutomationEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val requireAndroidAuto: StateFlow<Boolean> = prefs.requireAndroidAutoFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val slots: StateFlow<List<GateLocationSlot>> = prefs.slotsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GateLocationSlot.defaultSlots())

    val lastCallTimestamp: StateFlow<Long> = prefs.lastCallTimestampFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val isServiceRunning: StateFlow<Boolean> = AutoCallForegroundService.isRunning
    val closestGateStatus: StateFlow<ActiveGateStatus?> = AutoCallForegroundService.closestGateStatus
    val gateStatuses: StateFlow<Map<Int, ActiveGateStatus>> = AutoCallForegroundService.gateStatuses
    val serviceStatusMessage: StateFlow<String> = AutoCallForegroundService.statusMessage

    val carConnectionType: StateFlow<Int> = CarConnectionManager.connectionType

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    init {
        CarConnectionManager.init(application)
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun toggleAutomation(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            prefs.setAutomationEnabled(enabled)
            if (enabled) {
                val requireAA = prefs.isAndroidAutoRequired()
                if (!requireAA || carConnectionType.value == CarConnection.CONNECTION_TYPE_PROJECTION) {
                    AutoCallForegroundService.startService(context)
                    _userMessage.value = if (!requireAA)
                        "Automatyzacja włączona (tryb bez auta – serwis aktywny)."
                    else
                        "Automatyzacja włączona. Wykryto Android Auto – serwis uruchomiony."
                } else {
                    _userMessage.value = "Automatyzacja włączona. Oczekiwanie na połączenie z Android Auto."
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
            if (isAutomationEnabled.value) {
                if (!required) {
                    // Tryb testowy bez auta: uruchom natychmiast serwis
                    AutoCallForegroundService.startService(context)
                    _userMessage.value = "Tryb bez auta: serwis GPS uruchomiony."
                } else {
                    // Wymagane AA: zatrzymaj serwis jeśli nie ma projekcji
                    if (carConnectionType.value != CarConnection.CONNECTION_TYPE_PROJECTION) {
                        AutoCallForegroundService.stopService(context)
                        _userMessage.value = "Wymagane Android Auto: serwis oczekuje na połączenie z autem."
                    }
                }
            } else {
                _userMessage.value = if (required)
                    "Sprawdzanie Android Auto: WŁĄCZONE"
                else
                    "Sprawdzanie Android Auto: WYŁĄCZONE (tryb bez auta)"
            }
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
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
            _userMessage.value = "Wykonano połączenie testowe pod numer $phoneNumber"
        } catch (e: Exception) {
            _userMessage.value = "Błąd wywołania połączenia: ${e.message}"
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

    fun simulateAndroidAutoConnection(context: Context, connected: Boolean) {
        val connectionType = if (connected) {
            CarConnection.CONNECTION_TYPE_PROJECTION
        } else {
            CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        }

        val intent = Intent(CarConnection.ACTION_CAR_CONNECTION_UPDATED).apply {
            putExtra(CarConnectionReceiver.EXTRA_SIMULATED_TYPE, connectionType)
            setPackage(context.packageName)
        }
        CarConnectionManager.updateState(connectionType, simulated = true)
        context.sendBroadcast(intent)

        _userMessage.value = if (connected) {
            "Symulacja: Połączono z Android Auto (PROJECTION)"
        } else {
            "Symulacja: Rozłączono Android Auto (NOT_CONNECTED)"
        }
    }
}
