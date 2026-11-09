package com.autobrama.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.car.app.connection.CarConnection
import com.autobrama.app.connection.CarConnectionManager
import com.autobrama.app.data.PreferencesManager
import com.autobrama.app.service.AutoCallForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CarConnectionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarConnectionReceiver"
        const val EXTRA_SIMULATED_TYPE = "extra_simulated_connection_type"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "Otrzymano broadcast: action=$action")

        if (action == CarConnection.ACTION_CAR_CONNECTION_UPDATED ||
            action == "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        ) {
            val pendingResult = goAsync()

            val simulatedType = intent.getIntExtra(EXTRA_SIMULATED_TYPE, -1)
            val connectionType: Int
            if (simulatedType != -1) {
                connectionType = simulatedType
                CarConnectionManager.updateState(connectionType, simulated = true)
            } else {
                connectionType = try {
                    CarConnection(context).type.value ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                } catch (e: Exception) {
                    Log.e(TAG, "Nie można odczytać CarConnection.type", e)
                    CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                }
                CarConnectionManager.updateState(connectionType, simulated = false)
            }

            Log.i(TAG, "Wykryty stan połączenia: $connectionType")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = PreferencesManager(context)
                    val isAutomationEnabled = prefs.isAutomationEnabled()
                    val requireAA = prefs.isAndroidAutoRequired()

                    when (connectionType) {
                        CarConnection.CONNECTION_TYPE_PROJECTION -> {
                            Log.i(TAG, "Stan: CONNECTION_TYPE_PROJECTION (Android Auto aktywne)")
                            if (isAutomationEnabled) {
                                Log.i(TAG, "Automatyzacja włączona w DataStore -> Uruchamianie AutoCallForegroundService")
                                AutoCallForegroundService.startService(context)
                            } else {
                                Log.i(TAG, "Automatyzacja wyłączona w DataStore -> Brak akcji")
                            }
                        }

                        CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> {
                            Log.i(TAG, "Stan: CONNECTION_TYPE_NOT_CONNECTED, requireAA=$requireAA")
                            if (requireAA) {
                                Log.i(TAG, "Wymagane Android Auto -> Zatrzymywanie AutoCallForegroundService")
                                AutoCallForegroundService.stopService(context)
                            } else {
                                Log.i(TAG, "Android Auto niewymagane (tryb bez auta) -> Serwis pozostaje aktywny")
                            }
                        }

                        else -> {
                            Log.d(TAG, "Stan połączenia: $connectionType (inny niż PROJECTION lub NOT_CONNECTED)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Błąd w CarConnectionReceiver", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
