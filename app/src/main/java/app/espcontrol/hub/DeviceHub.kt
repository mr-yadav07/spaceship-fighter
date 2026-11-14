package app.espcontrol.hub

import android.content.Context
import app.espcontrol.core.Capabilities
import app.espcontrol.core.DeviceInfo
import app.espcontrol.core.Hex
import app.espcontrol.core.LinkState
import app.espcontrol.core.RgbState
import app.espcontrol.core.RouteKind
import app.espcontrol.data.DeviceStore
import app.espcontrol.data.PairedDevice
import app.espcontrol.protocol.ProtocolV2
import app.espcontrol.security.OwnerKeyStore
import app.espcontrol.transport.BleTransport
import app.espcontrol.transport.Rx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** BLE-only device hub. No ESPCTL handshake, encryption session, UDP or relay routing. */
class DeviceHub(ctx: Context, @Suppress("UNUSED_PARAMETER") keys: OwnerKeyStore, private val store: DeviceStore) {
    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appCtx.getSharedPreferences("transport_config", Context.MODE_PRIVATE)
    private val ble = BleTransport(appCtx)
    private val links = linkedMapOf<String, Link>()
    private val byAddress = hashMapOf<String,String>()
    private val lock = Mutex()
    private val devicesFlow = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = devicesFlow.asStateFlow()
    val scanStatus: StateFlow<String> = ble.scanStatus
    private val eventFlow = MutableSharedFlow<String>(extraBufferCapacity=16,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = eventFlow.asSharedFlow()
    private var started=false
    private val savedPairs by lazy { store.load().associateBy { it.id.lowercase() } }

    private data class Link(
        val id:String, val address:String, var name:String, var state:LinkState=LinkState.OFFLINE,
        var paired:Boolean=false, var caps:Capabilities?=null, val rgb:HashMap<Int,RgbState> = hashMapOf(),
        val switches:HashMap<Int,Boolean> = hashMapOf(), var lastError:String?=null,
        val pending:HashMap<String,ByteArray> = hashMapOf(), val sendJobs:HashMap<String,Job> = hashMapOf()
    )

    fun start() {
        if(started) return
        started=true
        scope.launch {
            launch { ble.incoming.collect { onRx(it) } }
            launch { ble.adverts.collect { onAdvert(it) } }
            launch { ble.ready.collect { ready ->
                lock.withLock {
                    links.values.forEach { l ->
                        if(l.address in ready && l.state==LinkState.CONNECTING) {
                            l.state=LinkState.ONLINE
                            send(l,ProtocolV2.CAPS_REQUEST)
                            send(l,ProtocolV2.STATUS_REQUEST)
                        } else if(l.address !in ready && l.state==LinkState.ONLINE) { l.state=LinkState.OFFLINE; l.caps=null; l.rgb.clear(); l.switches.clear(); l.lastError=null }
                    }
                    publish()
                }
            } }
            ble.start(this)
        }
    }

    private suspend fun onAdvert(a:BleTransport.Advert) = lock.withLock {
        val id=a.deviceId.lowercase()
        val l=links[id] ?: Link(id,a.address,a.name).also { links[id]=it }
        l.name=a.name
        savedPairs[id]?.let { saved -> l.name=saved.name.ifBlank { l.name }; l.paired=true }
        byAddress[a.address]=id
        publish()
    }

    private suspend fun onRx(rx:Rx) = lock.withLock {
        val id=byAddress[rx.source] ?: return@withLock
        val l=links[id] ?: return@withLock
        val f=ProtocolV2.decode(rx.bytes) ?: return@withLock
        when(f.type) {
            ProtocolV2.CAPS -> {
                val caps=ProtocolV2.parseCaps(f.payload) ?: run { l.lastError="Invalid capabilities packet"; publish(); return@withLock }
                l.caps=caps
                l.name=caps.name.ifBlank { l.name }
                l.paired=true
                l.state=LinkState.ONLINE
                persist()
            }
            ProtocolV2.UPDATE, ProtocolV2.STATUS -> {
                val entries=ProtocolV2.parseStates(f.payload) ?: run { l.lastError="Invalid status packet"; publish(); return@withLock }
                entries.forEach { e -> when(e) {
                    is ProtocolV2.StateEntry.Switch -> l.switches[e.id]=e.on
                    is ProtocolV2.StateEntry.Rgb -> l.rgb[e.id]=RgbState(e.on,e.r,e.g,e.b,e.brightness,(l.rgb[e.id]?.effectActive ?: false))
                    is ProtocolV2.StateEntry.Effect -> {
                        val old=l.rgb[e.id] ?: RgbState()
                        l.rgb[e.id]=old.copy(effectActive=e.effectId!=0)
                    }
                } }
            }
        }
        l.lastError=null
        publish()
    }

    private fun publish() {
        devicesFlow.value=links.values.map { l ->
            DeviceInfo(l.id,l.name,0,false,l.paired,l.state,
                if(l.address.isNotBlank()) setOf(RouteKind.BLE) else emptySet(),
                if(l.state==LinkState.ONLINE) RouteKind.BLE else null,l.caps,
                HashMap(l.rgb),HashMap(l.switches),l.lastError)
        }.sortedWith(compareByDescending<DeviceInfo>{it.paired}.thenBy{it.name})
    }

    private fun persist() { store.save(links.values.filter{it.paired}.map{PairedDevice(it.id,it.name,0)}) }

    private suspend fun send(l:Link,type:Int,payload:ByteArray=byteArrayOf()):Boolean {
        if(!ble.isReady(l.address)) return false
        val ok=ble.send(l.address,ProtocolV2.encode(type,payload))
        if(!ok) l.lastError="BLE write failed"
        return ok
    }

    private fun queue(l:Link,key:String,bytes:ByteArray) {
        l.pending[key]=bytes
        l.sendJobs[key]?.cancel()
        l.sendJobs[key]=scope.launch {
            delay(18)
            lock.withLock {
                val latest=l.pending.remove(key) ?: return@withLock
                l.sendJobs.remove(key)
                if(l.state==LinkState.ONLINE) send(l,latest[0].toInt() and 255,latest.copyOfRange(1,latest.size))
                publish()
            }
        }
    }

    fun connect(id:String) { scope.launch {
        lock.withLock {
            val l=links[id.lowercase()] ?: return@withLock
            if(l.state==LinkState.ONLINE || l.state==LinkState.CONNECTING) return@withLock
            l.state=LinkState.CONNECTING; l.caps=null; l.rgb.clear(); l.switches.clear(); l.lastError=null; publish()
            ble.connect(l.address)
        }
    } }
    fun disconnect(id:String) { scope.launch {
        lock.withLock { links[id.lowercase()]?.let { it.state=LinkState.OFFLINE; it.caps=null; it.rgb.clear(); it.switches.clear(); ble.disconnect(it.address); publish() } }
    } }
    fun forget(id:String) { scope.launch {
        lock.withLock { links.remove(id.lowercase())?.let { ble.disconnect(it.address) }; persist(); publish() }
    } }
    fun rescan() { ble.ensureScanning() }
    fun onPermissionsChanged() { ble.ensureScanning() }

    fun setRgb(id:String,out:Int,r:Int,g:Int,b:Int) { updateRgb(id,out) { it.copy(r=r.coerceIn(0,255),g=g.coerceIn(0,255),b=b.coerceIn(0,255)) } }
    fun setBrightness(id:String,out:Int,value:Int) { updateRgb(id,out) { it.copy(brightness=value.coerceIn(0,255)) } }
    fun setPower(id:String,out:Int,on:Boolean) { updateRgb(id,out) { it.copy(on=on) } }
    private fun updateRgb(id:String,out:Int,change:(RgbState)->RgbState) { scope.launch {
        lock.withLock {
            val l=links[id.lowercase()] ?: return@withLock
            val old=l.rgb[out] ?: RgbState()
            val next=change(old)
            l.rgb[out]=next
            val p=ProtocolV2.rgbs(listOf(ProtocolV2.RgbCommand(out,next.r,next.g,next.b,next.brightness,next.on)))
            queue(l,"rgb:$out",byteArrayOf(ProtocolV2.RGB_SET.toByte())+p)
            publish()
        }
    } }
    fun setOnOff(id:String,out:Int,on:Boolean) { scope.launch {
        lock.withLock {
            val l=links[id.lowercase()] ?: return@withLock
            l.switches[out]=on
            queue(l,"switch:$out",byteArrayOf(ProtocolV2.SWITCH_SET.toByte())+ProtocolV2.switches(listOf(out to on)))
            publish()
        }
    } }
    fun getStatus(id:String) { scope.launch { lock.withLock { links[id.lowercase()]?.let { send(it,ProtocolV2.STATUS_REQUEST) } } } }
    fun triggerEffect(id:String,out:Int,effectId:Int,start:Boolean) { scope.launch {
        lock.withLock {
            val l=links[id.lowercase()] ?: return@withLock
            val selected=if(start) effectId else 0
            queue(l,"effect:$out",byteArrayOf(ProtocolV2.EFFECT_SET.toByte())+ProtocolV2.effects(listOf(ProtocolV2.EffectCommand(out,selected,128))))
        }
    } }
    fun sendEffectData(@Suppress("UNUSED_PARAMETER") id:String,@Suppress("UNUSED_PARAMETER") effectId:Int,@Suppress("UNUSED_PARAMETER") index:Int,@Suppress("UNUSED_PARAMETER") total:Int,@Suppress("UNUSED_PARAMETER") data:ByteArray) {
        eventFlow.tryEmit("Custom effect data is not part of BLE protocol v2")
    }

    fun transportConfig():Map<String,String> = mapOf(
        "ble_service" to (prefs.getString("ble_service","a6c60001-6b9f-4d8e-a251-5bd248e8c101") ?: ""),
        "ble_command" to (prefs.getString("ble_command","a6c60002-6b9f-4d8e-a251-5bd248e8c101") ?: ""),
        "ble_events" to (prefs.getString("ble_events","a6c60003-6b9f-4d8e-a251-5bd248e8c101") ?: ""),
        "ble_prefix" to (prefs.getString("ble_prefix","ESPC-") ?: "ESPC-"),
        "relay_host" to "", "relay_port" to "47900", "udp_app_port" to "47901", "udp_device_port" to "47902"
    )
    fun configureTransport(service:String,command:String,events:String,prefix:String,
        @Suppress("UNUSED_PARAMETER") relayHost:String,@Suppress("UNUSED_PARAMETER") relayPort:Int,
        @Suppress("UNUSED_PARAMETER") udpAppPort:Int,@Suppress("UNUSED_PARAMETER") udpDevicePort:Int):Boolean {
        if(!ble.configure(service,command,events,prefix)) return false
        prefs.edit().putString("ble_service",service.trim()).putString("ble_command",command.trim())
            .putString("ble_events",events.trim()).putString("ble_prefix",prefix.trim()).apply()
        return true
    }
    fun changeOwnerKey(@Suppress("UNUSED_PARAMETER") key:String) {
        eventFlow.tryEmit("Owner-key security was removed in BLE protocol v2")
    }
}
