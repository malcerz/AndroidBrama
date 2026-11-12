package com.autobrama.app.connection

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NearbyBleDevice(val address: String, val name: String, val rssi: Int)

/**
 * Passive BLE radar detection, without GATT pairing or disrupting ANT+.
 * Advertisement reception indicates proximity, not necessarily riding.
 */
object RadarBleManager {
    private const val TAG = "RadarBle"
    private const val DISCOVERY_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = RadarPresenceTracker()
    private val found = linkedMapOf<String, NearbyBleDevice>()
    private val _devices = MutableStateFlow<List<NearbyBleDevice>>(emptyList())
    val devices: StateFlow<List<NearbyBleDevice>> = _devices.asStateFlow()
    private val _present = MutableStateFlow(false)
    val present: StateFlow<Boolean> = _present.asStateFlow()
    private val _status = MutableStateFlow("Radar BLE: nie skonfigurowano")
    val status: StateFlow<String> = _status.asStateFlow()

    private var context: Context? = null
    private var monitor = false
    private var targetAddress = ""
    private var targetName = ""
    private var discoveryUntil = 0L
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var filterKey: String? = null
    private var nextRetryAfter = 0L

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (discoveryUntil > 0L && now >= discoveryUntil) {
                discoveryUntil = 0L
                context?.let { updateScanner(it) }
            }
            val fresh = monitor && tracker.isNearby(now)
            if (_present.value != fresh) _present.value = fresh
            if (monitor && scanner == null && now >= nextRetryAfter) {
                context?.let { updateScanner(it) }
            }
            if (monitor || discoveryUntil > now) handler.postDelayed(this, 5_000L)
        }
    }

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return fine &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
        }
        return fine
    }

    fun setMonitoring(context: Context, enabled: Boolean, address: String, name: String) {
        val app = context.applicationContext
        handler.post {
            this.context = app
            val normalized = address.trim().uppercase()
            if (targetAddress != normalized) {
                targetAddress = normalized
                targetName = name
                tracker.reset()
                _present.value = false
            }
            monitor = enabled && normalized.isNotBlank()
            if (!monitor) {
                tracker.reset()
                _present.value = false
            }
            updateScanner(app)
        }
    }

    fun startDiscovery(context: Context) {
        val app = context.applicationContext
        handler.post {
            this.context = app
            found.clear()
            _devices.value = emptyList()
            discoveryUntil = SystemClock.elapsedRealtime() + DISCOVERY_MS
            nextRetryAfter = 0L
            updateScanner(app)
        }
    }

    fun stopDiscovery() {
        handler.post {
            discoveryUntil = 0L
            context?.let { updateScanner(it) }
        }
    }

    private fun stopScan() {
        val previous = scanner
        val callback = scanCallback
        scanner = null
        scanCallback = null
        filterKey = null
        if (previous != null && callback != null) {
            try { previous.stopScan(callback) }
            catch (e: Exception) { Log.w(TAG, "BLE stopScan failed", e) }
        }
    }

    @Suppress("MissingPermission")
    private fun updateScanner(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val discovering = discoveryUntil > now
        val wanted = discovering || monitor
        handler.removeCallbacks(heartbeat)
        if (!wanted) {
            stopScan()
            _status.value = "Radar BLE: wykrywanie wyłączone"
            return
        }
        handler.postDelayed(heartbeat, 5_000L)
        if (!hasPermission(context)) {
            stopScan()
            _present.value = false
            _status.value = "Brak uprawnień Bluetooth / dokładnej lokalizacji"
            nextRetryAfter = now + 30_000L
            return
        }
        val adapter = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        } catch (_: Exception) { null }
        if (adapter == null || !adapter.isEnabled) {
            stopScan()
            _present.value = false
            _status.value = "Bluetooth jest wyłączony"
            nextRetryAfter = now + 30_000L
            return
        }
        val newFilterKey = if (discovering) "discover" else "target:$targetAddress"
        if (scanner != null && filterKey == newFilterKey) return
        if (now < nextRetryAfter) return
        stopScan()
        try {
            val bleScanner = adapter.bluetoothLeScanner ?: run {
                _status.value = "Brak skanera Bluetooth LE"
                nextRetryAfter = now + 30_000L
                return
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            // A filtered scan can continue while the Android screen is off.
            val filters = if (discovering) emptyList() else listOf(
                ScanFilter.Builder().setDeviceAddress(targetAddress).build()
            )
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    record(result)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { record(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE scan failed: $errorCode")
                    _status.value = "Błąd skanowania BLE: $errorCode"
                    nextRetryAfter = SystemClock.elapsedRealtime() + 30_000L
                    stopScan()
                }
            }
            bleScanner.startScan(filters, settings, callback)
            scanner = bleScanner
            scanCallback = callback
            filterKey = newFilterKey
            _status.value = if (discovering) "Skanowanie urządzeń BLE (30 s)"
                else "Nasłuch radaru $targetName"
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start BLE scan", e)
            stopScan()
            nextRetryAfter = now + 30_000L
            _status.value = "Nie można skanować BLE"
        }
    }

    @Suppress("MissingPermission")
    private fun record(result: ScanResult) {
        val address = try { result.device.address?.uppercase() ?: return }
        catch (_: SecurityException) { return }
        val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            ?: try { result.device.name?.takeIf { it.isNotBlank() } }
                catch (_: SecurityException) { null }
            ?: "(bez nazwy)"
        val now = SystemClock.elapsedRealtime()
        if (discoveryUntil > now) {
            found[address] = NearbyBleDevice(address, name, result.rssi)
            _devices.value = found.values.sortedByDescending { it.rssi }.take(60)
        }
        if (monitor && address == targetAddress) {
            tracker.seen(now)
            if (tracker.isNearby(now)) {
                _present.value = true
                _status.value = "Radar w pobliżu: $name (${result.rssi} dBm)"
            }
        }
    }
}
