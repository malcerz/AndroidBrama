package com.autobrama.app.connection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** -1 means not yet observed; never interpret missing data as a disconnect. */
object CarConnectionManager {
    private const val TAG = "CarConnectionManager"
    const val UNKNOWN = -1

    private val _connectionType = MutableStateFlow(UNKNOWN)
    val connectionType: StateFlow<Int> = _connectionType.asStateFlow()

    private var carConnection: CarConnection? = null
    private var observer: Observer<Int>? = null
    private var isSimulated = false

    fun init(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { init(context) }
            return
        }
        if (carConnection != null) return
        try {
            val connection = CarConnection(context.applicationContext)
            val liveObserver = Observer<Int> { state ->
                if (!isSimulated) _connectionType.value = state
            }
            connection.type.observeForever(liveObserver)
            carConnection = connection
            observer = liveObserver
        } catch (e: Exception) {
            Log.e(TAG, "Nie udało się uruchomić nasłuchu Android Auto", e)
            carConnection = null
            observer = null
        }
    }

    /** The broadcast is a hint to re-query CarConnection, not a trusted state. */
    fun refresh(context: Context) {
        Handler(Looper.getMainLooper()).post {
            init(context)
            try {
                val data = CarConnection(context.applicationContext).type
                lateinit var oneShot: Observer<Int>
                oneShot = Observer { state ->
                    if (!isSimulated) _connectionType.value = state
                    data.removeObserver(oneShot)
                }
                data.observeForever(oneShot)
            } catch (e: Exception) {
                Log.e(TAG, "Nie udało się odświeżyć stanu Android Auto", e)
            }
        }
    }

    /** Only for a debug-only UI. Never accept simulation from exported broadcasts. */
    fun updateState(type: Int, simulated: Boolean = false) {
        isSimulated = simulated
        _connectionType.value = type
    }
}
