package com.autobrama.app.connection

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NearbyBleDevice(val address: String, val name: String, val rssi: Int)

/**
 * Passive BLE radar detection, without GATT pairing or disrupting ANT+.
 * Advertisement reception indicates proximity, not necessarily riding.
 *
 * All mutable state is confined to the main thread (handler, scan callbacks
 * and the Bluetooth state receiver all run there).
 */
object RadarBleManager {
    private const val TAG = "RadarBle"
    private const val DISCOVERY_MS = 30_000L
    private const val HEARTBEAT_MS = 5_000L
    private const val RETRY_AFTER_FAILURE_MS = 30_000L
    // Android may silently downgrade a scan that runs for a long time
    // (about 30 minutes), so a monitoring scan is restarted before that.
    private const val SCAN_RESTART_MS = 25 * 60_000L
    private const val NO_NAME = "(bez nazwy)"

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
    private var scanStartedAt = 0L
    private var nextRetryAfter = 0L
    private var stateReceiverRegistered = false

    private val heartbeat = Runnable {
        val now = SystemClock.elapsedRealtime()
        val fresh = monitor && tracker.isNearby(now)
        if (_present.value != fresh) {
            _present.value = fresh
            if (!fresh && monitor) {
                _status.value = "Brak świeżego sygnału radaru BLE " +
                    "(${RadarPresenceTracker.DEFAULT_HOLD_MILLIS / 1000} s)"
            }
        }
        // Re-validates Bluetooth, location and permissions, ends an expired
        // discovery, restarts a failed or long-running scan and reschedules
        // this heartbeat while scanning is still wanted.
        context?.let { updateScanner(it) }
    }

    /**
     * Turning Bluetooth off drops every registered scan without any callback.
     * Without this receiver a quick off/on cycle would leave the manager
     * believing that a dead scan is still running.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    stopScan()
                    clearPresence()
                    _status.value = "Bluetooth jest wyłączony"
                }
                BluetoothAdapter.STATE_ON -> {
                    nextRetryAfter = 0L
                    context?.let { updateScanner(it) }
                }
            }
        }
    }

    /** Scanning itself needs only BLUETOOTH_SCAN (plus location); no connection is made. */
    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return fine &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_SCAN
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
                clearPresence()
            }
            targetName = name
            monitor = enabled && normalized.isNotBlank()
            if (!monitor) clearPresence()
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

    private fun clearPresence() {
        tracker.reset()
        _present.value = false
    }

    private fun isUsableName(name: String): Boolean = name.isNotBlank() && name != NO_NAME

    private fun isLocationEnabled(context: Context): Boolean = try {
        LocationManagerCompat.isLocationEnabled(
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        )
    } catch (_: Exception) { true }

    private fun setStateReceiver(context: Context, wanted: Boolean) {
        if (wanted == stateReceiverRegistered) return
        try {
            if (wanted) {
                ContextCompat.registerReceiver(
                    context, bluetoothStateReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.unregisterReceiver(bluetoothStateReceiver)
            }
            stateReceiverRegistered = wanted
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth state receiver change failed", e)
            if (!wanted) stateReceiverRegistered = false
        }
    }

    @Suppress("MissingPermission")
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
            setStateReceiver(context, false)
            _status.value = "Radar BLE: wykrywanie wyłączone"
            return
        }
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        setStateReceiver(context, true)
        if (!hasPermission(context)) {
            stopScan()
            clearPresence()
            _status.value = "Brak uprawnień Bluetooth / dokładnej lokalizacji"
            return
        }
        val adapter = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        } catch (_: Exception) { null }
        if (adapter == null || !adapter.isEnabled) {
            stopScan()
            clearPresence()
            _status.value = "Bluetooth jest wyłączony"
            return
        }
        if (!isLocationEnabled(context)) {
            // Android delivers no BLE scan results while system location is off.
            stopScan()
            clearPresence()
            _status.value = "Lokalizacja w telefonie jest wyłączona – skan BLE nie działa"
            return
        }
        val newFilterKey = if (discovering) "discover" else "target:$targetAddress|$targetName"
        val tooOld = !discovering && scanner != null && now - scanStartedAt >= SCAN_RESTART_MS
        if (scanner != null && filterKey == newFilterKey && !tooOld) return
        if (now < nextRetryAfter) return
        stopScan()
        try {
            val bleScanner = adapter.bluetoothLeScanner ?: run {
                _status.value = "Brak skanera Bluetooth LE"
                nextRetryAfter = now + RETRY_AFTER_FAILURE_MS
                return
            }
            // Discovery is short and runs with the screen on, so it may listen
            // continuously; background monitoring stays in low-power mode.
            val settings = ScanSettings.Builder()
                .setScanMode(
                    if (discovering) ScanSettings.SCAN_MODE_LOW_LATENCY
                    else ScanSettings.SCAN_MODE_LOW_POWER
                )
                .build()
            // A filtered scan can continue while the Android screen is off.
            // Filters are alternatives: the saved address, or the advertised
            // name when the radar shows up under a different (random) address.
            val filters: List<ScanFilter> = if (discovering) emptyList() else buildList {
                add(ScanFilter.Builder().setDeviceAddress(targetAddress).build())
                if (isUsableName(targetName)) {
                    add(ScanFilter.Builder().setDeviceName(targetName).build())
                }
            }
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    record(result)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { record(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    // A late failure of a replaced scan must not stop the current one.
                    if (scanCallback !== this) return
                    Log.w(TAG, "BLE scan failed: $errorCode")
                    _status.value = "Błąd skanowania BLE: $errorCode"
                    nextRetryAfter = SystemClock.elapsedRealtime() + RETRY_AFTER_FAILURE_MS
                    stopScan()
                }
            }
            // Publish the state first so a failure reported during startScan
            // is attributed to this scan.
            scanner = bleScanner
            scanCallback = callback
            filterKey = newFilterKey
            scanStartedAt = now
            bleScanner.startScan(filters, settings, callback)
            if (discovering) {
                _status.value = "Skanowanie urządzeń BLE (30 s)"
            } else if (!_present.value) {
                _status.value = "Nasłuch radaru $targetName"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start BLE scan", e)
            stopScan()
            nextRetryAfter = now + RETRY_AFTER_FAILURE_MS
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
            ?: NO_NAME
        val now = SystemClock.elapsedRealtime()
        if (discoveryUntil > now) {
            found[address] = NearbyBleDevice(address, name, result.rssi)
            _devices.value = found.values.sortedByDescending { it.rssi }.take(60)
        }
        val isTarget = address == targetAddress ||
            (isUsableName(targetName) && name == targetName)
        if (monitor && isTarget) {
            tracker.seen(now)
            if (tracker.isNearby(now)) {
                _present.value = true
                _status.value = "Radar w pobliżu: $name (${result.rssi} dBm)"
            }
        }
    }
}
