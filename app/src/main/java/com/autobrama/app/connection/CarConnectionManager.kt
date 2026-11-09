package com.autobrama.app.connection

import android.content.Context
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CarConnectionManager {
    private const val TAG = "CarConnectionManager"

    private val _connectionType = MutableStateFlow(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
    val connectionType: StateFlow<Int> = _connectionType.asStateFlow()

    private var carConnection: CarConnection? = null
    private var observer: Observer<Int>? = null
    private var isSimulated: Boolean = false

    fun init(context: Context) {
        if (carConnection != null) return

        try {
            val appCtx = context.applicationContext
            carConnection = CarConnection(appCtx)
            observer = Observer<Int> { type ->
                Log.d(TAG, "CarConnection changed to: $type")
                if (!isSimulated) {
                    _connectionType.value = type
                }
            }
            carConnection?.type?.observeForever(observer!!)
            carConnection?.type?.value?.let { current ->
                if (!isSimulated && current != CarConnection.CONNECTION_TYPE_NOT_CONNECTED) {
                    _connectionType.value = current
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CarConnection", e)
        }
    }

    fun updateState(type: Int, simulated: Boolean = false) {
        isSimulated = simulated
        _connectionType.value = type
    }
}
