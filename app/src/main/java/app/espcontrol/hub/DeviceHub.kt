package app.espcontrol.hub

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import app.espcontrol.BuildConfig
import app.espcontrol.core.Capabilities
import app.espcontrol.core.DeviceInfo
import app.espcontrol.core.Hex
import app.espcontrol.core.LinkState
import app.espcontrol.core.RgbState
import app.espcontrol.core.RouteKind
import app.espcontrol.data.DeviceStore
import app.espcontrol.data.PairedDevice
import app.espcontrol.protocol.Crypto
import app.espcontrol.protocol.Header
import app.espcontrol.protocol.PacketCodec
import app.espcontrol.protocol.Payloads
import app.espcontrol.protocol.ReplayWindow
import app.espcontrol.protocol.StatusEntry
import app.espcontrol.protocol.Wire
import app.espcontrol.security.OwnerKeyStore
import app.espcontrol.transport.BleTransport
import app.espcontrol.transport.LocalUdpTransport
import app.espcontrol.transport.RelayUdpTransport
import app.espcontrol.transport.Rx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.EnumMap
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns every device, the three routes (local UDP, BLE, relay UDP), pairing, session security,
 * automatic route selection, keep-alive, reconnect and status feedback.
 * All state mutations run on one single-threaded dispatcher, so no locks are needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceHub(ctx: Context, private val keys: OwnerKeyStore, private val store: DeviceStore) {
    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val configPrefs = appCtx.getSharedPreferences("transport_config", Context.MODE_PRIVATE)
    private val ble = BleTransport(appCtx)
    private val udp = LocalUdpTransport(appCtx)
    private val relay = RelayUdpTransport(
        configPrefs.getString("relay_host", BuildConfig.RELAY_HOST) ?: BuildConfig.RELAY_HOST,
        configPrefs.getInt("relay_port", BuildConfig.RELAY_PORT),
    ) { registrationPacket() }

    private class RouteInfo { @Volatile var lastRx = 0L; var endpoint: String? = null }

    private class Link(val id: String, val idBytes: ByteArray) {
        var name = "ESP-" + id.takeLast(4).uppercase()
        var type = 0
        var fw = 0
        var authorized = false
        var paired = false
        var auto = true
        var state = LinkState.OFFLINE
        var lastError: String? = null
        val routes = EnumMap<RouteKind, RouteInfo>(RouteKind::class.java)
        var session = 0
        var key: ByteArray? = null
        val txSeq = AtomicInteger(0)
        val replay = ReplayWindow()
        var caps: Capabilities? = null
        val rgb = HashMap<Int, RgbState>()
        val onoff = HashMap<Int, Boolean>()
        var lastTx = 0L
        var lastStatusRx = 0L
        var lastResponse = 0L
        var connecting = false
        var lastConnectTry = 0L
        var nonceA: ByteArray? = null
        var waiter: CompletableDeferred<Boolean>? = null
        val verifyJobs = HashMap<String, Job>()
    }

    private val links = HashMap<String, Link>()
    private val devicesFlow = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = devicesFlow.asStateFlow()
    private val eventFlow = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Human-readable notices (e.g. "Device reports no data"). */
    val events: SharedFlow<String> = eventFlow.asSharedFlow()

    @Volatile private var pairedIds: List<ByteArray> = emptyList()
    private var started = false
    private var discoverySeq = 0

    private fun now() = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (started) return
        started = true
        scope.launch {
            store.load().forEach { p ->
                val id = p.id.lowercase()
                val idBytes = Hex.parse(id) ?: return@forEach
                val l = links.getOrPut(id) { Link(id, idBytes) }
                l.name = p.name.ifBlank { l.name }; l.type = p.type; l.paired = true
            }
            publish()
            listOf(ble, udp, relay).forEach { t -> launch { t.incoming.collect { onRx(it) } } }
            launch { ble.adverts.collect { a -> onAdvert(a) } }
            launch { ble.ready.collect { publish() } }
            ble.start(this)
            udp.start(this)
            relay.start(this)
            watchNetwork()
            launch { discoveryLoop() }
            launch { housekeeping() }
        }
    }

    private fun watchNetwork() {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scope.launch { delay(500); udp.restart(); relay.restart() } }
                override fun onLost(network: Network) { scope.launch { udp.restart() } }
            })
        } catch (e: Exception) {
            // network callbacks unavailable: sockets still self-heal on errors
        }
    }


    fun transportConfig(): Map<String, String> = mapOf(
        "ble_service" to (configPrefs.getString("ble_service", "a6c60001-6b9f-4d8e-a251-5bd248e8c101") ?: ""),
        "ble_command" to (configPrefs.getString("ble_command", "a6c60002-6b9f-4d8e-a251-5bd248e8c101") ?: ""),
        "ble_events" to (configPrefs.getString("ble_events", "a6c60003-6b9f-4d8e-a251-5bd248e8c101") ?: ""),
        "ble_prefix" to (configPrefs.getString("ble_prefix", "ESPC-") ?: "ESPC-"),
        "relay_host" to (configPrefs.getString("relay_host", BuildConfig.RELAY_HOST) ?: ""),
        "relay_port" to configPrefs.getInt("relay_port", BuildConfig.RELAY_PORT).toString(),
        "udp_app_port" to configPrefs.getInt("udp_app_port", Wire.PORT_APP).toString(),
        "udp_device_port" to configPrefs.getInt("udp_device_port", Wire.PORT_DEVICE).toString(),
    )

    fun configureTransport(service: String, command: String, events: String, prefix: String, relayHost: String, relayPort: Int, udpAppPort: Int, udpDevicePort: Int): Boolean {
        if (relayPort !in 1..65535 || udpAppPort !in 1..65535 || udpDevicePort !in 1..65535) return false
        if (!ble.configure(service, command, events, prefix)) return false
        configPrefs.edit().putString("relay_host", relayHost.trim()).putInt("relay_port", relayPort).apply()
        relay.configure(relayHost, relayPort)
        udp.configure(udpAppPort, udpDevicePort)
        eventFlow.tryEmit("Connection settings saved")
        return true
    }
    /** Call after the user grants Bluetooth permissions. */
    fun onPermissionsChanged() { scope.launch { ble.ensureScanning() } }

    fun rescan() { scope.launch { sendDiscover() } }

    // ------------------------------------------------------------------ discovery

    private suspend fun discoveryLoop() {
        while (true) {
            sendDiscover()
            delay(3000)
        }
    }

    private suspend fun sendDiscover() {
        val na = Crypto.random(8)
        val hint = Crypto.hmac(keys.authKey(), "HINT".toByteArray(), na).copyOf(8)
        val pkt = PacketCodec.encode(Wire.T_DISCOVER, ByteArray(6) { 0xFF.toByte() }, 0, ++discoverySeq, Payloads.discover(na, hint), null)
        udp.broadcast(pkt)
    }

    private fun onAdvert(a: BleTransport.Advert) {
        val idBytes = Hex.parse(a.deviceId) ?: return
        val l = links.getOrPut(a.deviceId) { Link(a.deviceId, idBytes) }
        markRx(l, RouteKind.BLE, a.address)
        publish()
    }

    // ------------------------------------------------------------------ routing helpers

    private fun markRx(l: Link, route: RouteKind, source: String) {
        val info = l.routes.getOrPut(route) { RouteInfo() }
        info.lastRx = now()
        if (route != RouteKind.RELAY_UDP) info.endpoint = source
    }

    private fun alive(l: Link, r: RouteKind): Boolean {
        val info = l.routes[r] ?: return false
        return when (r) {
            RouteKind.LOCAL_UDP -> info.endpoint != null && now() - info.lastRx < ALIVE_MS
            RouteKind.RELAY_UDP -> now() - info.lastRx < ALIVE_MS
            RouteKind.BLE -> info.endpoint?.let { ble.isReady(it) } == true
        }
    }

    /** BLE is the active transport in this release; UDP and relay remain visible but dormant. */
    private fun best(l: Link): RouteKind? = listOf(RouteKind.BLE).firstOrNull { alive(l, it) }

    private suspend fun transmit(l: Link, route: RouteKind, bytes: ByteArray): Boolean {
        val ep = l.routes[route]?.endpoint
        return when (route) {
            RouteKind.LOCAL_UDP -> ep != null && udp.sendTo(ep, bytes)
            RouteKind.BLE -> ep != null && ble.send(ep, bytes)
            RouteKind.RELAY_UDP -> relay.send(bytes)
        }
    }

    private suspend fun sendEnc(l: Link, type: Int, payload: ByteArray): Boolean {
        val key = l.key ?: return false
        val route = best(l) ?: return false
        val seq = l.txSeq.incrementAndGet()
        val raw = PacketCodec.encode(type, l.idBytes, l.session, seq, payload, key)
        l.lastTx = now()
        return transmit(l, route, raw)
    }

    private suspend fun sendReliable(l: Link, type: Int, payload: ByteArray): Boolean {
        repeat(3) {
            val sent = now()
            if (!sendEnc(l, type, payload)) { l.lastError = "No route"; return false }
            val deadline = sent + 400
            while (now() < deadline) {
                if (l.lastResponse >= sent) return true
                delay(25)
            }
        }
        l.lastError = "No response"
        return false
    }

    // ------------------------------------------------------------------ receive

    private fun onRx(rx: Rx) {
        val h = PacketCodec.peek(rx.bytes) ?: return
        if (h.type == Wire.T_RELAY_LIST || h.type == Wire.T_RELAY_OK) { onRelayControl(h, rx); return }
        val id = Hex.of(h.deviceId)
        val l = links.getOrPut(id) { Link(id, h.deviceId) }
        when (h.type) {
            Wire.T_ANNOUNCE -> onAnnounce(l, h, rx)
            Wire.T_HELLO_RESP -> onHelloResp(l, h, rx)
            else -> onSessionPacket(l, h, rx)
        }
        publish()
    }

    private fun onRelayControl(h: Header, rx: Rx) {
        if (h.type != Wire.T_RELAY_LIST) return
        val p = PacketCodec.payload(rx.bytes, h, null, Wire.DIR_DEV_TO_APP) ?: return
        val list = Payloads.parseRelayList(p) ?: return
        for (d in list) {
            val id = Hex.of(d.id)
            val l = links.getOrPut(id) { Link(id, d.id) }
            l.type = d.type
            l.authorized = true
            if (d.online) markRx(l, RouteKind.RELAY_UDP, "relay")
        }
        publish()
    }

    private fun onAnnounce(l: Link, h: Header, rx: Rx) {
        if (h.encrypted) return
        val p = PacketCodec.payload(rx.bytes, h, null, Wire.DIR_DEV_TO_APP) ?: return
        val a = Payloads.parseAnnounce(p) ?: return
        markRx(l, rx.route, rx.source)
        if (a.name.isNotBlank()) l.name = a.name
        l.type = a.type; l.fw = a.fw; l.authorized = a.authorized
    }

    private fun onHelloResp(l: Link, h: Header, rx: Rx) {
        val p = PacketCodec.payload(rx.bytes, h, null, Wire.DIR_DEV_TO_APP) ?: return
        val na = l.nonceA ?: return
        if (p.size < 36) return
        val nd = p.copyOfRange(0, 16)
        val sid = p.copyOfRange(16, 20)
        val mac = p.copyOfRange(20, 36)
        val auth = keys.authKey()
        val expect = Crypto.hmac(auth, "RESP".toByteArray(), na, nd, sid, l.idBytes).copyOf(16)
        if (!Crypto.constantTimeEquals(mac, expect)) return // wrong key / forged: ignore silently
        l.session = ByteBuffer.wrap(sid).int
        l.key = Crypto.sessionKey(auth, na, nd)
        l.replay.reset()
        l.txSeq.set(0)
        markRx(l, rx.route, rx.source)
        l.waiter?.complete(true)
    }

    private fun onSessionPacket(l: Link, h: Header, rx: Rx) {
        if (!h.encrypted) {
            if (h.type == Wire.T_ERROR) {
                val p = PacketCodec.payload(rx.bytes, h, null, Wire.DIR_DEV_TO_APP) ?: return
                if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == Wire.E_AUTH) l.waiter?.complete(false)
            }
            return
        }
        val key = l.key ?: return
        if (h.session != l.session) return
        val p = PacketCodec.payload(rx.bytes, h, key, Wire.DIR_DEV_TO_APP) ?: return
        if (!l.replay.accept(h.seq)) return
        markRx(l, rx.route, rx.source)
        l.lastResponse = now()
        when (h.type) {
            Wire.T_CAPS -> Payloads.parseCaps(p)?.let { c ->
                l.caps = c; l.type = c.type; l.fw = c.fw
                if (c.name.isNotBlank()) l.name = c.name
                persist()
            }
            Wire.T_STATUS -> Payloads.parseStatus(p)?.let { applyStatus(l, it); l.lastStatusRx = now() }
            Wire.T_NO_DATA_1 -> eventFlow.tryEmit("${l.name}: no data (1/2)")
            Wire.T_NO_DATA_2 -> eventFlow.tryEmit("${l.name}: no data (2/2)")
            Wire.T_ERROR -> if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == Wire.E_NO_SESSION) {
                l.key = null; l.state = LinkState.OFFLINE // device rebooted: re-handshake automatically
            }
        }
    }

    private fun applyStatus(l: Link, entries: List<StatusEntry>) {
        for (e in entries) when (e) {
            is StatusEntry.Rgb -> {
                val c = l.rgb[e.out] ?: RgbState()
                l.rgb[e.out] = c.copy(
                    on = e.on ?: c.on, r = e.r ?: c.r, g = e.g ?: c.g, b = e.b ?: c.b,
                    brightness = e.brightness ?: c.brightness, effectActive = e.effect ?: c.effectActive,
                )
            }
            is StatusEntry.OnOff -> l.onoff[e.out] = e.state
        }
    }

    // ------------------------------------------------------------------ pairing / connect

    fun connect(id: String) { scope.launch { links[id]?.auto = true; doConnect(id) } }

    private suspend fun doConnect(id: String): Boolean {
        val l = links[id] ?: return false
        if (l.connecting) return false
        l.connecting = true; l.state = LinkState.CONNECTING; l.lastError = null; publish()
        try {
            var route = best(l)
            if (route == null) {
                val ep = l.routes[RouteKind.BLE]?.endpoint
                if (ep != null) {
                    ble.connect(ep)
                    var waited = 0
                    while (waited < 8000 && !ble.isReady(ep)) { delay(200); waited += 200 }
                }
                route = best(l)
            }
            if (route == null) { l.state = LinkState.OFFLINE; l.lastError = "Device not reachable"; publish(); return false }
            val auth = keys.authKey()
            repeat(3) {
                val na = Crypto.random(16)
                l.nonceA = na
                val mac = Crypto.hmac(auth, "HELLO".toByteArray(), na, l.idBytes).copyOf(16)
                val pkt = PacketCodec.encode(Wire.T_HELLO_REQ, l.idBytes, 0, 0, Payloads.helloReq(na, mac), null)
                val w = CompletableDeferred<Boolean>()
                l.waiter = w
                transmit(l, best(l) ?: route!!, pkt)
                val ok = withTimeoutOrNull(1500) { w.await() }
                l.waiter = null
                if (ok == true) {
                    l.state = LinkState.ONLINE; l.authorized = true; l.paired = true
                    l.lastResponse = now()
                    persist(); publish()
                    sendEnc(l, Wire.T_CAPS_GET, ByteArray(0))
                    sendEnc(l, Wire.T_GET_STATUS, Payloads.getStatus(Wire.ALL_OUTPUTS))
                    return true
                }
                if (ok == false) {
                    l.state = LinkState.UNAUTHORIZED; l.authorized = false; l.lastError = "Owner Key rejected"; publish()
                    return false
                }
            }
            l.state = LinkState.OFFLINE; l.lastError = "No answer from device"; publish()
            return false
        } finally {
            l.connecting = false
        }
    }

    fun disconnect(id: String) {
        scope.launch {
            val l = links[id] ?: return@launch
            l.auto = false; l.key = null; l.state = LinkState.OFFLINE
            l.routes[RouteKind.BLE]?.endpoint?.let { ble.disconnect(it) }
            publish()
        }
    }

    fun forget(id: String) {
        scope.launch {
            val l = links.remove(id) ?: return@launch
            l.routes[RouteKind.BLE]?.endpoint?.let { ble.disconnect(it) }
            persist(); publish()
            relay.sendRegistration()
        }
    }

    /** Replaces the Owner Key; every session is dropped and re-established with the new key. */
    fun changeOwnerKey(newKey: String) {
        scope.launch {
            keys.set(newKey)
            links.values.forEach { it.key = null; it.state = LinkState.OFFLINE; it.authorized = false }
            relay.sendRegistration()
            publish()
        }
    }

    // ------------------------------------------------------------------ control API

    private val latest = HashMap<String, suspend () -> Unit>()
    private var flushJob: Job? = null

    /** Latest-value-wins coalescing for continuous controls (max ~30 packets/s per output). */
    private fun submitLatest(key: String, action: suspend () -> Unit) {
        latest[key] = action
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (latest.isNotEmpty()) {
                val batch = latest.values.toList()
                latest.clear()
                batch.forEach { it() }
                delay(33)
            }
        }
    }

    private fun scheduleVerify(l: Link, out: Int) {
        val k = "${l.id}:$out"
        l.verifyJobs[k]?.cancel()
        val changedAt = now()
        l.verifyJobs[k] = scope.launch {
            delay(900) // the device sends status 500 ms after the last update; ask only if it did not
            if (l.state == LinkState.ONLINE && l.lastStatusRx < changedAt) sendReliable(l, Wire.T_GET_STATUS, Payloads.getStatus(out))
        }
    }

    fun setRgb(id: String, out: Int, r: Int, g: Int, b: Int) {
        scope.launch {
            val l = links[id] ?: return@launch
            l.rgb[out] = (l.rgb[out] ?: RgbState()).copy(r = r, g = g, b = b)
            publish()
            submitLatest("$id:$out:rgb") { sendEnc(l, Wire.T_SET_RGB, Payloads.setRgb(out, r, g, b)) }
            scheduleVerify(l, out)
        }
    }

    fun setBrightness(id: String, out: Int, level: Int) {
        scope.launch {
            val l = links[id] ?: return@launch
            l.rgb[out] = (l.rgb[out] ?: RgbState()).copy(brightness = level)
            publish()
            submitLatest("$id:$out:br") { sendEnc(l, Wire.T_SET_BRIGHT, Payloads.setBrightness(out, level)) }
            scheduleVerify(l, out)
        }
    }

    fun setPower(id: String, out: Int, on: Boolean) {
        scope.launch {
            val l = links[id] ?: return@launch
            l.rgb[out] = (l.rgb[out] ?: RgbState()).copy(on = on)
            publish()
            sendReliable(l, Wire.T_SET_POWER, Payloads.setPower(out, on))
            publish()
        }
    }

    fun setOnOff(id: String, out: Int, on: Boolean) {
        scope.launch {
            val l = links[id] ?: return@launch
            l.onoff[out] = on
            publish()
            sendReliable(l, Wire.T_SET_ONOFF, Payloads.setOnOff(out, on))
            publish()
        }
    }

    fun getStatus(id: String, out: Int = Wire.ALL_OUTPUTS) {
        scope.launch { links[id]?.let { sendReliable(it, Wire.T_GET_STATUS, Payloads.getStatus(out)) }; publish() }
    }

    fun triggerEffect(id: String, out: Int, effectId: Int, start: Boolean) {
        scope.launch { links[id]?.let { sendReliable(it, Wire.T_EFFECT_TRIGGER, Payloads.effectTrigger(out, effectId, start)) }; publish() }
    }

    /** Sends effect data in <=100-byte chunks; each chunk is acknowledged. */
    fun sendEffectData(id: String, effectId: Int, data: ByteArray) {
        scope.launch {
            val l = links[id] ?: return@launch
            val chunks = data.toList().chunked(100)
            for ((i, c) in chunks.withIndex()) {
                if (!sendReliable(l, Wire.T_EFFECT_DATA, Payloads.effectData(effectId, i, chunks.size, c.toByteArray()))) break
            }
            publish()
        }
    }

    // ------------------------------------------------------------------ housekeeping

    private suspend fun housekeeping() {
        while (true) {
            delay(500)
            ble.ensureScanning()
            val t = now()
            for (l in links.values.toList()) {
                val bi = l.routes[RouteKind.BLE]
                val addr = bi?.endpoint
                if (l.paired && l.auto && bi != null && addr != null && t - bi.lastRx < 6000 && !ble.isReady(addr)) ble.connect(addr)
                val best = best(l)
                if (l.state == LinkState.ONLINE && best == null) { l.state = LinkState.OFFLINE; l.lastError = "Connection lost" }
                if (l.state == LinkState.ONLINE && best != null && t - l.lastTx > 900) scope.launch { sendEnc(l, Wire.T_PING, ByteArray(0)) }
                if (l.state == LinkState.OFFLINE && l.paired && l.auto && best != null && !l.connecting && t - l.lastConnectTry > 2500) {
                    l.lastConnectTry = t
                    scope.launch { doConnect(l.id) }
                }
            }
            publish()
        }
    }

    // ------------------------------------------------------------------ publish / persist / relay

    private fun persist() {
        store.save(links.values.filter { it.paired }.map { PairedDevice(it.id, it.name, it.type) })
    }

    private fun publish() {
        val t = now()
        pairedIds = links.values.filter { it.paired }.map { it.idBytes }
        devicesFlow.value = links.values.map { l ->
            val av = EnumSet.noneOf(RouteKind::class.java)
            for ((r, i) in l.routes) {
                if (t - i.lastRx < 6000 || (r == RouteKind.BLE && i.endpoint?.let { ble.isReady(it) } == true)) av.add(r)
            }
            DeviceInfo(
                id = l.id, name = l.name, type = l.type, authorized = l.authorized, paired = l.paired,
                state = l.state, availableRoutes = av, activeRoute = if (l.state == LinkState.ONLINE) best(l) else null,
                caps = l.caps, rgbStates = HashMap(l.rgb), onOffStates = HashMap(l.onoff), lastError = l.lastError,
            )
        }.sortedWith(compareByDescending<DeviceInfo> { it.paired }.thenBy { it.name })
    }

    private fun registrationPacket(): ByteArray? {
        val payload = Payloads.relayRegister(1, Crypto.ownerId(keys.authKey()), pairedIds)
        return PacketCodec.encode(Wire.T_RELAY_REGISTER, ByteArray(6), 0, 0, payload, null)
    }

    companion object {
        private const val ALIVE_MS = 5000L
    }
}
