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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android Auto broadcast is only a change hint, not an authoritative connection
 * state. External extras are deliberately ignored to prevent spoofed projection.
 */
class CarConnectionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CarConnectionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CarConnection.ACTION_CAR_CONNECTION_UPDATED) return
        CarConnectionManager.init(context)
        CarConnectionManager.refresh(context)

        // A running standby service observes changes directly. This is a best
        // effort recovery path if Android stopped it; background starts can be
        // refused by the OS on recent Android versions.
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(1200) // wait for the asynchronous CarConnection query
                val enabled = withContext(Dispatchers.IO) {
                    PreferencesManager(appContext).isAutomationEnabled()
                }
                if (enabled && !AutoCallForegroundService.isRunning.value &&
                    CarConnectionManager.connectionType.value == CarConnection.CONNECTION_TYPE_PROJECTION
                ) {
                    val requested = AutoCallForegroundService.startService(appContext)
                    if (!requested) {
                        Log.w(TAG, "Android odmówił uruchomienia usługi z tła. Otwórz aplikację.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Błąd obsługi zmiany Android Auto", e)
            } finally {
                pending.finish()
            }
        }
    }
}
