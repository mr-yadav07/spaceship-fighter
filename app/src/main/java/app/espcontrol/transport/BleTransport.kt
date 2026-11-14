package app.espcontrol.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import app.espcontrol.core.RouteKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE route. Devices advertise the control service and a local name "ESPC-<12 hex MAC>".
 * Protocol v2 frames are written to COMMAND and received on EVENTS notifications.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val ctx: Context) : Transport {
    override val kind = RouteKind.BLE

    class Advert(val address: String, val deviceId: String, val name: String, val rssi: Int)

    private val flow = MutableSharedFlow<Rx>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: SharedFlow<Rx> = flow.asSharedFlow()
    private val advertFlow = MutableSharedFlow<Advert>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val adverts: SharedFlow<Advert> = advertFlow.asSharedFlow()
    private val readyFlow = MutableStateFlow<Set<String>>(emptySet())
    private val errorFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val errors: SharedFlow<Pair<String, String>> = errorFlow.asSharedFlow()
    private val scanStatusFlow = MutableStateFlow("Bluetooth status unknown")
    val scanStatus: StateFlow<String> = scanStatusFlow.asStateFlow()
    private var compatibleFound = 0
    private var rawFound = 0
    /** BLE addresses with a fully set-up GATT session. */
    val ready: StateFlow<Set<String>> = readyFlow.asStateFlow()

    private class Conn(val gatt: BluetoothGatt) {
        val writeLock = Mutex()
        @Volatile var pendingWrite: CompletableDeferred<Boolean>? = null
        @Volatile var cmd: BluetoothGattCharacteristic? = null
        @Volatile var ready = false
        @Volatile var mtu = 23
    }


    private val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val radioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> ensureScanning()
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    stopScan()
                    scanStatusFlow.value = "Bluetooth is OFF · turn it ON to scan"
                }
                BluetoothAdapter.STATE_TURNING_ON -> scanStatusFlow.value = "Bluetooth is turning ON…"
            }
        }
    }
    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(radioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(radioReceiver, filter)
    }
    private val prefs = ctx.getSharedPreferences("transport_config", Context.MODE_PRIVATE)
    @Volatile private var serviceUuid = readUuid("ble_service", "a6c60001-6b9f-4d8e-a251-5bd248e8c101")
    @Volatile private var commandUuid = readUuid("ble_command", "a6c60002-6b9f-4d8e-a251-5bd248e8c101")
    @Volatile private var eventsUuid = readUuid("ble_events", "a6c60003-6b9f-4d8e-a251-5bd248e8c101")
    @Volatile private var namePrefix = prefs.getString("ble_prefix", NAME_PREFIX) ?: NAME_PREFIX

    private fun readUuid(key: String, fallback: String): UUID =
        runCatching { UUID.fromString(prefs.getString(key, fallback) ?: fallback) }.getOrDefault(UUID.fromString(fallback))

    fun configure(service: String, command: String, events: String, prefix: String): Boolean {
        val svc = runCatching { UUID.fromString(service.trim()) }.getOrNull() ?: return false
        val cmd = runCatching { UUID.fromString(command.trim()) }.getOrNull() ?: return false
        val evt = runCatching { UUID.fromString(events.trim()) }.getOrNull() ?: return false
        val pfx = prefix.trim()
        if (pfx.isEmpty()) return false
        prefs.edit().putString("ble_service", svc.toString()).putString("ble_command", cmd.toString())
            .putString("ble_events", evt.toString()).putString("ble_prefix", pfx).apply()
        serviceUuid = svc; commandUuid = cmd; eventsUuid = evt; namePrefix = pfx
        conns.keys.toList().forEach { disconnect(it) }
        stopScan(); ensureScanning()
        return true
    }
    private val conns = ConcurrentHashMap<String, Conn>()
    private var scanning = false

    override fun start(scope: CoroutineScope) { ensureScanning() }

    override fun stop() {
        stopScan()
        conns.keys.toList().forEach { disconnect(it) }
    }

    fun granted(): Boolean {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 31) has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)
        else has(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun isReady(address: String) = conns[address]?.ready == true

    // ---- scanning ----
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            rawFound++
            val record = result.scanRecord
            val advertisedName = record?.deviceName?.takeIf { it.isNotBlank() }
                ?: runCatching { result.device.name }.getOrNull().orEmpty()
            val serviceAdvertised = record?.serviceUuids?.any { it.uuid == serviceUuid } == true
            val prefixMatch = advertisedName.startsWith(namePrefix, ignoreCase = true)
            if (!serviceAdvertised && !prefixMatch) {
                if (rawFound % 20 == 0) scanStatusFlow.value =
                    "Scanning… $rawFound BLE advertisements checked; no ESPControl device yet"
                return
            }
            val addressId = result.device.address.replace(":", "").lowercase().takeLast(12)
            val id = if (prefixMatch) advertisedName.substring(namePrefix.length).lowercase() else addressId
            if (!id.matches(Regex("[0-9a-f]{12}"))) return
            val displayName = advertisedName.ifBlank { namePrefix + id }
            compatibleFound++
            scanStatusFlow.value = "Found $compatibleFound ESPControl device(s)"
            advertFlow.tryEmit(Advert(result.device.address, id, displayName, result.rssi))
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            scanStatusFlow.value = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "BLE scan already running"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android could not register the BLE scanner"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning is unsupported on this phone"
                SCAN_FAILED_INTERNAL_ERROR -> "Android BLE scan internal error"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "BLE scan hardware resources unavailable"
                else -> "BLE scan failed · Android error $errorCode"
            }
        }
    }

    /** Safe to call repeatedly (e.g. after the user grants permissions). */
    fun ensureScanning() {
        if (scanning) return
        if (!granted()) {
            scanStatusFlow.value = if (Build.VERSION.SDK_INT >= 31) "Bluetooth scan/connect permission required" else "Location permission required for BLE scan"
            return
        }
        val adapter = mgr.adapter
        if (adapter == null) { scanStatusFlow.value = "This phone has no Bluetooth adapter"; return }
        if (!adapter.isEnabled) { scanStatusFlow.value = "Bluetooth is OFF · turn it ON to scan"; return }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { scanStatusFlow.value = "BLE scanner unavailable · check Bluetooth"; return }
        compatibleFound = 0
        rawFound = 0
        scanStatusFlow.value = "Starting active BLE scan…"
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0L)
            .build()
        try {
            scanner.startScan(null, settings, scanCb)
            scanning = true
            scanStatusFlow.value = "Scanning for " + namePrefix + " devices…"
        } catch (e: SecurityException) {
            scanning = false
            scanStatusFlow.value = "BLE permission denied · allow Nearby devices"
        } catch (e: Exception) {
            scanning = false
            scanStatusFlow.value = "Unable to start BLE scan · " + e.javaClass.simpleName
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try { mgr.adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { /* ignore */ }
        scanning = false
    }

    // ---- connection ----
    fun connect(address: String) {
        if (conns.containsKey(address)) return
        if (!granted()) {
            errorFlow.tryEmit(address to "Bluetooth permission missing · allow Nearby devices")
            return
        }
        val adapter = mgr.adapter
        if (adapter == null || !adapter.isEnabled) {
            errorFlow.tryEmit(address to "Bluetooth is OFF")
            return
        }
        try {
            val dev = adapter.getRemoteDevice(address)
            val g = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            if (g == null) errorFlow.tryEmit(address to "Android could not create a BLE GATT connection")
            else conns[address] = Conn(g)
        } catch (e: SecurityException) {
            errorFlow.tryEmit(address to "Bluetooth permission denied")
        } catch (e: Exception) {
            errorFlow.tryEmit(address to "BLE connect failed · " + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun disconnect(address: String) {
        val c = conns.remove(address) ?: return
        try { c.gatt.disconnect(); c.gatt.close() } catch (e: Exception) { /* ignore */ }
        publishReady()
    }

    private fun publishReady() { readyFlow.value = conns.filterValues { it.ready }.keys.toSet() }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val addr = g.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                val requested = runCatching { g.requestMtu(517) }.getOrDefault(false)
                if (!requested) g.discoverServices()
            } else {
                conns.remove(addr)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    errorFlow.tryEmit(addr to "BLE GATT connection failed · status $status")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    errorFlow.tryEmit(addr to "BLE device disconnected")
                }
                try { g.close() } catch (e: Exception) { /* ignore */ }
                publishReady()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            conns[g.device.address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = conns[g.device.address] ?: return
            val svc = g.getService(serviceUuid)
            val cmd = svc?.getCharacteristic(commandUuid)
            val evt = svc?.getCharacteristic(eventsUuid)
            val cccd = evt?.getDescriptor(CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || cmd == null || evt == null || cccd == null) {
                errorFlow.tryEmit(g.device.address to "GATT service/characteristic mismatch · check firmware UUIDs")
                g.disconnect()
                return
            }
            c.cmd = cmd
            if (!g.setCharacteristicNotification(evt, true)) {
                errorFlow.tryEmit(g.device.address to "Could not enable BLE notifications")
                g.disconnect()
                return
            }
            writeDescriptor(g, cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                errorFlow.tryEmit(g.device.address to "BLE notification subscription failed · status $status")
                g.disconnect()
                return
            }
            conns[g.device.address]?.ready = true
            publishReady()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            conns[g.device.address]?.pendingWrite?.let { deferred ->
                deferred.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            flow.tryEmit(Rx(kind, value, g.device.address))
        }

        // API < 33
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { flow.tryEmit(Rx(kind, it.copyOf(), g.device.address)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
    }

    @Suppress("DEPRECATION")
    suspend fun send(address: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val c = conns[address] ?: return@withContext false
        c.writeLock.withLock {
            val ch = c.cmd
            if (!c.ready || ch == null || bytes.size > c.mtu - 3) return@withLock false
            val completion = CompletableDeferred<Boolean>()
            c.pendingWrite = completion
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                c.gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = bytes
                c.gatt.writeCharacteristic(ch)
            }
            if (!accepted) {
                c.pendingWrite = null
                return@withLock false
            }
            val result = withTimeoutOrNull(1500L) { completion.await() } ?: false
            c.pendingWrite = null
            result
        }
    }

    companion object {
        const val NAME_PREFIX = "ESPC-"
        val SERVICE: UUID = UUID.fromString("a6c60001-6b9f-4d8e-a251-5bd248e8c101")
        val CHR_COMMAND: UUID = UUID.fromString("a6c60002-6b9f-4d8e-a251-5bd248e8c101")
        val CHR_EVENTS: UUID = UUID.fromString("a6c60003-6b9f-4d8e-a251-5bd248e8c101")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
