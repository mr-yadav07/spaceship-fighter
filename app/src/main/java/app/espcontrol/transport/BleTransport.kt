package app.espcontrol.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import app.espcontrol.core.RouteKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE route. Devices advertise the control service and a local name "ESPC-<12 hex MAC>".
 * Packets use exactly the same ESPCTL format as UDP: written to the COMMAND characteristic
 * (write without response) and received as notifications on the EVENTS characteristic.
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
    /** BLE addresses with a fully set-up GATT session. */
    val ready: StateFlow<Set<String>> = readyFlow.asStateFlow()

    private class Conn(val gatt: BluetoothGatt) {
        @Volatile var cmd: BluetoothGattCharacteristic? = null
        @Volatile var ready = false
        @Volatile var mtu = 23
    }

    private val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
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
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith(namePrefix)) return
            val id = name.removePrefix(namePrefix).lowercase()
            if (id.length != 12) return
            advertFlow.tryEmit(Advert(result.device.address, id, name, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) { scanning = false }
    }

    /** Safe to call repeatedly (e.g. after the user grants permissions). */
    fun ensureScanning() {
        if (scanning || !granted()) return
        val scanner = mgr.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { scanner.startScan(filters, settings, scanCb); scanning = true } catch (e: Exception) { scanning = false }
    }

    private fun stopScan() {
        if (!scanning) return
        try { mgr.adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { /* ignore */ }
        scanning = false
    }

    // ---- connection ----
    fun connect(address: String) {
        if (!granted() || conns.containsKey(address)) return
        val dev = mgr.adapter?.getRemoteDevice(address) ?: return
        val g = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        conns[address] = Conn(g)
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
                g.requestMtu(517)
            } else {
                conns.remove(addr)
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
            if (status != BluetoothGatt.GATT_SUCCESS || cmd == null || evt == null || cccd == null) { g.disconnect(); return }
            c.cmd = cmd
            g.setCharacteristicNotification(evt, true)
            writeDescriptor(g, cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { g.disconnect(); return }
            conns[g.device.address]?.ready = true
            publishReady()
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
        val ch = c.cmd
        if (!c.ready || ch == null || bytes.size > c.mtu - 3) return@withContext false
        if (Build.VERSION.SDK_INT >= 33) {
            c.gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = bytes
            c.gatt.writeCharacteristic(ch)
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
