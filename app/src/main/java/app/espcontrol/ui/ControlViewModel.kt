package app.espcontrol.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.espcontrol.EspApp
import app.espcontrol.core.DeviceInfo
import app.espcontrol.core.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScrollMode { CLASSIC, ACCEL, FREE }

/** One controllable output (an RGB strip or an ON/OFF channel) of one device. */
data class OutItem(
    val key: String, val devId: String, val out: Int, val label: String,
    val on: Boolean, val color: Color, val isRgb: Boolean,
)

data class UiState(
    val devices: List<DeviceInfo> = emptyList(),
    val rgbItems: List<OutItem> = emptyList(),
    val swItems: List<OutItem> = emptyList(),
    val selRgb: Set<String> = emptySet(),
    val selSw: Set<String> = emptySet(),
    val r: Int = 240, val g: Int = 93, val b: Int = 255, val br: Int = 128,
    val enR: Boolean = true, val enG: Boolean = true, val enB: Boolean = true,
    val msg: String = "Ready",
    val mode: ScrollMode = ScrollMode.CLASSIC,
    val sens: Map<ScrollMode, Int> = mapOf(ScrollMode.CLASSIC to 0, ScrollMode.ACCEL to 50, ScrollMode.FREE to 50),
    val demo: Boolean = false,
) {
    val effR get() = if (enR) r else 0
    val effG get() = if (enG) g else 0
    val effB get() = if (enB) b else 0
    val rgbPowerOn get() = rgbItems.any { it.key in selRgb && it.on }
}

class ControlViewModel(app: Application) : AndroidViewModel(app) {
    private val espApp = app as EspApp
    private val hub = espApp.hub
    val scanStatus = hub.scanStatus
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private data class Local(
        val selRgb: Set<String> = emptySet(), val selSw: Set<String> = emptySet(),
        val r: Int = 240, val g: Int = 93, val b: Int = 255, val br: Int = 128,
        val enR: Boolean = true, val enG: Boolean = true, val enB: Boolean = true,
        val msg: String = "Ready", val mode: ScrollMode = ScrollMode.CLASSIC,
        val sens: Map<ScrollMode, Int> = emptyMap(),
        val demo: Boolean = false, val demoRgb: List<OutItem> = emptyList(), val demoSw: List<OutItem> = emptyList(),
    )

    private val local = MutableStateFlow(
        Local(
            mode = ScrollMode.valueOf(prefs.getString("mode", ScrollMode.CLASSIC.name) ?: ScrollMode.CLASSIC.name),
            sens = mapOf(
                ScrollMode.CLASSIC to prefs.getInt("sens_classic", 0),
                ScrollMode.ACCEL to prefs.getInt("sens_accel", 50),
                ScrollMode.FREE to prefs.getInt("sens_free", 50),
            ),
            demo = prefs.getBoolean("demo", false),
            demoRgb = demoItems(true), demoSw = demoItems(false),
        ),
    )

    val ui: StateFlow<UiState> = combine(hub.devices, local) { devs, l -> build(devs, l) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch { hub.events.collect { m -> local.update { it.copy(msg = m) } } }
    }

    // ---------------------------------------------------------------- state building

    private fun demoItems(rgb: Boolean): List<OutItem> {
        val names = if (rgb) listOf("RGB Lamp", "RGB LED", "RGB 2", "Strip 4", "Bed LED") else listOf("LED 7", "LED 3", "OK 4", "Fan 2", "Door 3")
        return names.mapIndexed { i, n ->
            OutItem((if (rgb) "demoR" else "demoS") + i, "demo", i, n, rgb && i < 3 || !rgb && i == 2, Color(240, 93, 255), rgb)
        }
    }

    private fun build(devs: List<DeviceInfo>, l: Local): UiState {
        val rgb = ArrayList<OutItem>()
        val sw = ArrayList<OutItem>()
        if (l.demo) {
            rgb.addAll(l.demoRgb); sw.addAll(l.demoSw)
        } else {
            for (d in devs) {
                if (d.state != LinkState.ONLINE) continue
                val caps = d.caps ?: continue
                for (o in caps.rgb) {
                    val st = d.rgbStates[o.id]
                    val label = o.name.ifBlank { if (caps.rgb.size > 1) "${d.name} ${o.id + 1}" else d.name }
                    rgb.add(OutItem("${d.id}#r${o.id}", d.id, o.id, label, st?.on ?: false, Color(st?.r ?: 90, st?.g ?: 90, st?.b ?: 90), true))
                }
                for (o in caps.onoff) {
                    val label = o.name.ifBlank { if (caps.onoff.size > 1) "${d.name} ${o.id + 1}" else d.name }
                    sw.add(OutItem("${d.id}#s${o.id}", d.id, o.id, label, d.onOffStates[o.id] ?: false, Color.White, false))
                }
            }
        }
        return UiState(
            devices = devs, rgbItems = rgb, swItems = sw,
            selRgb = l.selRgb.filter { k -> rgb.any { it.key == k } }.toSet(),
            selSw = l.selSw.filter { k -> sw.any { it.key == k } }.toSet(),
            r = l.r, g = l.g, b = l.b, br = l.br, enR = l.enR, enG = l.enG, enB = l.enB,
            msg = l.msg, mode = l.mode, sens = l.sens, demo = l.demo,
        )
    }

    private fun items(isRgb: Boolean): List<OutItem> = ui.value.let { if (isRgb) it.rgbItems else it.swItems }

    private fun selected(isRgb: Boolean): List<OutItem> {
        val s = ui.value
        val keys = if (isRgb) s.selRgb else s.selSw
        return items(isRgb).filter { it.key in keys }
    }

    private fun demoUpdate(key: String, f: (OutItem) -> OutItem) = local.update { l ->
        l.copy(demoRgb = l.demoRgb.map { if (it.key == key) f(it) else it }, demoSw = l.demoSw.map { if (it.key == key) f(it) else it })
    }

    private fun msg(m: String) = local.update { it.copy(msg = m) }

    // ---------------------------------------------------------------- colour / brightness / power

    private fun pushColor() {
        val s = ui.value
        val targets = selected(true)
        if (s.demo) {
            targets.forEach { item -> demoUpdate(item.key) { x -> x.copy(color = Color(s.effR, s.effG, s.effB)) } }
        } else {
            val group=targets.groupBy { it.devId }.mapValues { (_,outputs) ->
                outputs.map { item ->
                    app.espcontrol.protocol.ProtocolV2.RgbCommand(item.out,s.effR,s.effG,s.effB,s.br,item.on)
                }
            }
            if(group.isNotEmpty()) hub.setRgbGroup(group)
        }
    }

    fun setColor(r: Int, g: Int, b: Int) { local.update { it.copy(r = r, g = g, b = b) }; pushColor() }
    fun setChannel(ch: Int, v: Int) {
        local.update { when (ch) { 0 -> it.copy(r = v); 1 -> it.copy(g = v); else -> it.copy(b = v) } }
        pushColor()
    }
    fun toggleChannel(ch: Int) {
        local.update { when (ch) { 0 -> it.copy(enR = !it.enR); 1 -> it.copy(enG = !it.enG); else -> it.copy(enB = !it.enB) } }
        pushColor()
    }

    fun setBrightness(v: Int) {
        local.update { it.copy(br = v) }
        val demo = ui.value.demo
        if (!demo) {
            val group=selected(true).groupBy { it.devId }.mapValues { (_,outputs) ->
                outputs.map { item ->
                    val state=ui.value.devices.firstOrNull { it.id==item.devId }?.rgbStates?.get(item.out)
                    app.espcontrol.protocol.ProtocolV2.RgbCommand(item.out,state?.r ?: ui.value.effR,state?.g ?: ui.value.effG,state?.b ?: ui.value.effB,v,item.on)
                }
            }
            if(group.isNotEmpty()) hub.setRgbGroup(group)
        }
    }

    fun togglePower() {
        val sel = selected(true)
        if (sel.isEmpty()) { msg("RGB: no device selected"); return }
        val target = !sel.any { it.on }
        for (it in sel) if (ui.value.demo) demoUpdate(it.key) { x -> x.copy(on = target) } else hub.setPower(it.devId, it.out, target)
        msg("RGB ${if (target) "ON" else "OFF"} · ${sel.size} outputs")
    }

    fun syncRgb() {
        val n = selected(true).size
        if (n == 0) { msg("Sync: no RGB selected"); return }
        pushColor()
        val s = ui.value
        if (!s.demo) for (it in selected(true)) hub.setBrightness(it.devId, it.out, s.br)
        msg("Synced colour to $n outputs")
    }

    // ---------------------------------------------------------------- selection / switches

    fun toggleSelect(item: OutItem) {
        local.update { l ->
            if (item.isRgb) l.copy(selRgb = if (item.key in l.selRgb) l.selRgb - item.key else l.selRgb + item.key)
            else l.copy(selSw = if (item.key in l.selSw) l.selSw - item.key else l.selSw + item.key)
        }
    }

    fun toggleItemOn(item: OutItem) {
        val target = !item.on
        if (ui.value.demo) demoUpdate(item.key) { it.copy(on = target) }
        else if (item.isRgb) hub.setPower(item.devId, item.out, target) else hub.setOnOff(item.devId, item.out, target)
    }

    fun dselect(isRgb: Boolean) {
        local.update { if (isRgb) it.copy(selRgb = emptySet()) else it.copy(selSw = emptySet()) }
        msg(if (isRgb) "RGB: all deselected" else "Switches: all deselected")
    }

    fun powerSwitches() {
        val sel = selected(false)
        if (sel.isEmpty()) { msg("Switches: none selected"); return }
        val target = !sel.any { it.on }
        for (it in sel) if (ui.value.demo) demoUpdate(it.key) { x -> x.copy(on = target) } else hub.setOnOff(it.devId, it.out, target)
        msg("Switches ${if (target) "ON" else "OFF"} · ${sel.size}")
    }

    fun syncSwitches(center: OutItem?) {
        val sel = selected(false)
        if (sel.isEmpty() || center == null) { msg("Sync: no switch selected"); return }
        for (it in sel) if (ui.value.demo) demoUpdate(it.key) { x -> x.copy(on = center.on) } else hub.setOnOff(it.devId, it.out, center.on)
        msg("Synced ${sel.size} switches to ${center.label} (${if (center.on) "ON" else "OFF"})")
    }

    /** Trigger an effect by its advertised name. Each selected device resolves that name
     * to its own local effect ID; unsupported devices are deliberately skipped. */
    fun triggerEffectByName(effectName: String) {
        val s = ui.value
        val targets = selected(true)
        if (targets.isEmpty()) { msg("Effects: select at least one RGB candidate"); return }
        if (s.demo) { msg("Effects are unavailable in Demo mode"); return }
        var applied = 0
        targets.groupBy { it.devId }.forEach { (deviceId, outputs) ->
            val device = s.devices.firstOrNull { it.id == deviceId } ?: return@forEach
            val effect = device.caps?.effects?.firstOrNull { it.name.equals(effectName, ignoreCase = true) }
                ?: return@forEach
            outputs.forEach { hub.triggerEffect(deviceId, it.out, effect.id, true); applied++ }
        }
        msg(if (applied > 0) "Effect $effectName requested on $applied RGB outputs" else "No selected device supports $effectName")
    }

    fun stopEffects() {
        val s = ui.value
        selected(true).groupBy { it.devId }.forEach { (deviceId, outputs) ->
            val device = s.devices.firstOrNull { it.id == deviceId } ?: return@forEach
            val effects = device.caps?.effects.orEmpty()
            outputs.forEach { out ->
                val active = effects.firstOrNull()
                if (active != null) hub.triggerEffect(deviceId, out.out, active.id, false)
            }
        }
        msg("Stop effect requested")
    }

    // ---------------------------------------------------------------- devices / security / settings

    fun connect(id: String) = hub.connect(id)
    fun disconnect(id: String) = hub.disconnect(id)
    fun forget(id: String) = hub.forget(id)
    fun rescan() { hub.rescan(); msg("Searching for devices…") }

    fun ownerKey(): String = espApp.keys.get()
    fun transportConfig(): Map<String, String> = hub.transportConfig()
    fun saveTransportConfig(c: Map<String, String>): Boolean {
        val port = c["relay_port"]?.toIntOrNull() ?: return false
        val ok = hub.configureTransport(
            c["ble_service"].orEmpty(), c["ble_command"].orEmpty(), c["ble_events"].orEmpty(),
            c["ble_prefix"].orEmpty(), c["relay_host"].orEmpty(), port,
            c["udp_app_port"]?.toIntOrNull() ?: return false, c["udp_device_port"]?.toIntOrNull() ?: return false,
        )
        msg(if (ok) "Connection settings saved" else "Invalid UUID, prefix or relay port")
        return ok
    }
    fun changeOwnerKey(k: String) { espApp.keys.set(k); hub.changeOwnerKey(k); msg("Legacy local setting saved; BLE v2 does not use it") }

    fun setMode(m: ScrollMode) { prefs.edit().putString("mode", m.name).apply(); local.update { it.copy(mode = m) } }
    fun setSens(v: Int) {
        val m = local.value.mode
        prefs.edit().putInt("sens_" + m.name.lowercase(), v).apply()
        local.update { it.copy(sens = it.sens + (m to v)) }
    }
    fun setDemo(on: Boolean) { prefs.edit().putBoolean("demo", on).apply(); local.update { it.copy(demo = on) } }
}
