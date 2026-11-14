package app.espcontrol.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.espcontrol.core.DeviceInfo
import app.espcontrol.core.LinkState
import app.espcontrol.core.RouteKind

private enum class Panel { NONE, SETTINGS, SCAN, RGB, SWITCH, MENU }
private enum class Sheet { NONE, DEVICES, SECURITY, SCROLL, CONNECTION, EFFECTS, FIRMWARE }

@Composable
fun MainScreen(vm: ControlViewModel) {
    val s by vm.ui.collectAsState()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var infoOpen by remember { mutableStateOf(false) }

    val eff = Triple(s.effR, s.effG, s.effB)
    val anySelOn = s.rgbPowerOn
    val discShade = 0.75f * (1f - s.br / 255f)
    val connected = s.devices.count { it.state == LinkState.ONLINE }

    val centerRgb = centerItem(s.rgbItems)
    val centerSw = centerItem(s.swItems)

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(C.Bg1, C.Bg2)))
            .clip(RoundedCornerShape(0.dp)),
    ) {
        // glow behind the disc
        Box(
            Modifier.offset(29.dp, 239.dp).size(330.dp).clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        0f to Color(eff.first, eff.second, eff.third).copy(alpha = if (anySelOn) (0.45f * (0.3f + 0.7f * s.br / 255f)) else 0f),
                        1f to Color.Transparent,
                    ),
                ),
        )

        InfoCard(s, connected, centerRgb, centerSw, onOpen = { infoOpen = true })

        ColorDisc(
            Modifier.offset(102.dp, 312.dp).size(184.dp),
            eff, discShade,
        ) { r, g, b -> vm.setColor(r, g, b) }
        Box(Modifier.offset(102.dp, 312.dp).size(184.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(Color(0xFF0E0F14))
                    .border(3.dp, if (anySelOn) C.Green else C.Text, CircleShape)
                    .clickable { vm.togglePower() },
                contentAlignment = Alignment.Center,
            ) { T(if (anySelOn) "ON" else "OFF", 15f, weight = FontWeight.SemiBold) }
        }

        Column(Modifier.offset(288.dp, 302.dp).width(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(C.Track).padding(horizontal = 6.dp, vertical = 2.dp)) { T("${s.br}", 11f, weight = FontWeight.SemiBold) }
        }
        VSlider(s.br, 255, { vm.setBrightness(it) }, Modifier.offset(314.dp, 312.dp).size(14.dp, 184.dp))

        ChannelSlider(96.dp, 520.dp, "R", s.r, s.enR, C.RedCh, { vm.setChannel(0, it) }, { vm.toggleChannel(0) })
        ChannelSlider(96.dp, 562.dp, "G", s.g, s.enG, C.GreenCh, { vm.setChannel(1, it) }, { vm.toggleChannel(1) })
        ChannelSlider(96.dp, 604.dp, "B", s.b, s.enB, C.BlueCh, { vm.setChannel(2, it) }, { vm.toggleChannel(2) })

        ActionRow(18.dp, 676.dp, "RGB", { vm.syncRgb() }, { vm.dselect(true) }, { vm.togglePower() })
        ActionRow(18.dp, 722.dp, "Switches", { vm.syncSwitches(centerSw) }, { vm.dselect(false) }, { vm.powerSwitches() })

        AnimatedVisibility(panel != Panel.NONE, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            when (panel) {
                Panel.SETTINGS -> SettingsRevolver(vm, s) { open -> sheet = open; if (open != Sheet.NONE) panel = Panel.NONE }
                Panel.SCAN -> ScanPanel(s, vm) { panel = Panel.NONE }
                Panel.RGB -> ListRevolver(s.rgbItems, s.selRgb, s.mode, s.sens[s.mode] ?: 0, vm::toggleSelect, vm::toggleItemOn)
                Panel.SWITCH -> ListRevolver(s.swItems, s.selSw, s.mode, s.sens[s.mode] ?: 0, vm::toggleSelect, vm::toggleItemOn)
                Panel.MENU -> MenuPanel { panel = Panel.NONE }
                else -> {}
            }
        }
        AnimatedVisibility(infoOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            InfoDetailPlaceholder { infoOpen = false }
        }
        AnimatedVisibility(sheet != Sheet.NONE, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            when (sheet) {
                Sheet.DEVICES -> DevicesSheet(s, vm) { sheet = Sheet.NONE }
                Sheet.SECURITY -> SecuritySheet(vm) { sheet = Sheet.NONE }
                Sheet.CONNECTION -> ConnectionSheet(vm) { sheet = Sheet.NONE }
                Sheet.SCROLL -> ScrollSheet(s, vm) { sheet = Sheet.NONE }
                Sheet.EFFECTS -> EffectsSheet(s, vm) { sheet = Sheet.NONE }
                Sheet.FIRMWARE -> FirmwareSheet(s) { sheet = Sheet.NONE }
                else -> {}
            }
        }

        SideColumn(
            panel,
            onSettings = { panel = if (panel == Panel.SETTINGS) Panel.NONE else Panel.SETTINGS },
            onScan = { panel = if (panel == Panel.SCAN) Panel.NONE else Panel.SCAN },
            onRgb = { panel = if (panel == Panel.RGB) Panel.NONE else Panel.RGB },
            onSwitch = { panel = if (panel == Panel.SWITCH) Panel.NONE else Panel.SWITCH },
            onMenu = { panel = if (panel == Panel.MENU) Panel.NONE else Panel.MENU },
        )
    }
}

/** No rotation tracked here (that lives inside Revolver); Sync targets the first selected switch, or the first switch. */
private fun centerItem(items: List<OutItem>): OutItem? = items.firstOrNull { it.on } ?: items.firstOrNull()

@Composable
private fun InfoCard(s: UiState, connected: Int, centerRgb: OutItem?, centerSw: OutItem?, onOpen: () -> Unit) {
    Column(
        Modifier.offset(58.dp, 48.dp).width(274.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0x12FFFFFF), Color(0x06FFFFFF))))
            .border(1.dp, C.Line, RoundedCornerShape(28.dp))
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("INFORMATION", 11f, C.Muted, FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (s.demo) C.Orange else C.Green))
                T(if (s.demo) "DEMO" else "LIVE", 11f, if (s.demo) C.Orange else C.Green, FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(6.dp))
        InfoRow("Connected", "$connected devices")
        InfoRow("RGB", "${s.selRgb.size}/${s.rgbItems.size} sel · ${s.rgbItems.count { it.on }} on")
        InfoRow("Switches", "${s.selSw.size}/${s.swItems.size} sel · ${s.swItems.count { it.on }} on")
        InfoRow("Colour", "R ${if (s.enR) s.r else "–"} · G ${if (s.enG) s.g else "–"} · B ${if (s.enB) s.b else "–"}")
        InfoRow("Brightness", "${(s.br * 100 / 255)}%")
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x18FFFFFF)))
        T(s.msg, 12f, C.Warn, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        T(label, 12.5f, C.Muted, modifier = Modifier.width(96.dp))
        T(value, 12.5f, C.Text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChannelSlider(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, letter: String, value: Int, on: Boolean, color: Color, onChange: (Int) -> Unit, onToggle: () -> Unit) {
    HSlider(value, 255, if (on) color else C.Off, onChange, Modifier.offset(x, y).width(212.dp))
    ChannelButton(letter, on, color, if (letter == "B") Color.White else Color(0xFF0E0F14), onToggle, Modifier.offset(316.dp, y - 4.dp))
}

@Composable
private fun ActionRow(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, label: String, onSync: () -> Unit, onDsel: () -> Unit, onPower: () -> Unit) {
    Row(Modifier.offset(x, y).fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        T(label, 11f, C.Muted, modifier = Modifier.width(52.dp))
        Spacer(Modifier.width(6.dp))
        PillButton("Sync", 60.dp, onSync)
        Spacer(Modifier.width(6.dp))
        PillButton("DSelect", 70.dp, onDsel)
        Spacer(Modifier.width(6.dp))
        PillButton("On/Off", 60.dp, onPower)
        Spacer(Modifier.width(18.dp))
    }
}

@Composable
private fun SideColumn(
    panel: Panel, onSettings: () -> Unit, onScan: () -> Unit,
    onRgb: () -> Unit, onSwitch: () -> Unit, onMenu: () -> Unit,
) {
    Column(Modifier.offset(306.dp, 294.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
        SideBtn(panel == Panel.SETTINGS, onSettings) { GearIcon() }
        SideBtn(panel == Panel.SCAN, onScan) { T("Scan", 11f) }
        SideBtn(panel == Panel.RGB, onRgb) { T("RGB", 11f) }
        SideBtn(panel == Panel.SWITCH, onSwitch) { T("0/1", 11f) }
        SideBtn(panel == Panel.MENU, onMenu) { MenuIcon() }
    }
}

@Composable
private fun SideBtn(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(42.dp, 42.dp)
            .clip(RoundedCornerShape(topStart = 21.dp, bottomStart = 21.dp))
            .background(if (active) Color(0xFF1E2740) else Color(0xDB1E212D))
            .border(1.dp, if (active) C.Accent else C.Line, RoundedCornerShape(topStart = 21.dp, bottomStart = 21.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun InfoDetailPlaceholder(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(40.dp, 150.dp).width(310.dp)
                .clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(24.dp))
                .padding(16.dp),
        ) {
            T("Details", 17f, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            T("Tap a row in Information for its description and quick actions.", 13f, C.Muted)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BackBtn(onClose) }
        }
    }
}

@Composable
private fun MenuPanel(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(130.dp, 304.dp).width(212.dp)
                .clip(RoundedCornerShape(22.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(22.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (m in listOf("Connections", "Firmware update", "Backup & restore", "Help", "About")) {
                Box(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp)).clickable {}.padding(start = 14.dp), contentAlignment = Alignment.CenterStart) { T(m, 14f) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BackBtn(onClose) }
        }
    }
}

@Composable
private fun ListRevolver(
    items: List<OutItem>, selected: Set<String>, mode: ScrollMode, sens: Int,
    onSelect: (OutItem) -> Unit, onToggle: (OutItem) -> Unit,
) {
    Revolver(
        items = items.map { RevItem(it.label, sel = it.key in selected, on = it.on, dot = if (it.isRgb) it.color else null) },
        mode = mode, sens = sens,
        onName = { i -> items.getOrNull(i)?.let(onSelect) },
        onToggle = { i -> items.getOrNull(i)?.let(onToggle) },
    )
}

@Composable
private fun SettingsRevolver(vm: ControlViewModel, s: UiState, openSheet: (Sheet) -> Unit) {
    val items = listOf("Devices" to Sheet.DEVICES, "Effects catalogue" to Sheet.EFFECTS, "Firmware / GPIO setup" to Sheet.FIRMWARE, "Connection config" to Sheet.CONNECTION, "Security (Owner Key)" to Sheet.SECURITY, "Scroll" to Sheet.SCROLL, "Demo data" to Sheet.NONE)
    Revolver(
        items = items.mapIndexed { i, (n, _) -> RevItem(n, sel = false, on = i == 6 && s.demo, dot = null) },
        mode = s.mode, sens = s.sens[s.mode] ?: 0,
        onName = { i -> if (items[i].second == Sheet.NONE) vm.setDemo(!s.demo) else openSheet(items[i].second) },
        onToggle = { i -> if (items[i].second == Sheet.NONE) vm.setDemo(!s.demo) },
    )
}

@Composable
private fun ScanPanel(s: UiState, vm: ControlViewModel, onClose: () -> Unit) {
    val scanStatus by vm.scanStatus.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(84.dp, 220.dp).width(258.dp)
                .clip(RoundedCornerShape(26.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(26.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T(if (s.demo) "Devices · DEMO DATA ON" else "Devices · BLE", 16f, weight = FontWeight.SemiBold)
            LazyColumn(Modifier.height(260.dp)) {
                items(s.devices) { d -> DeviceRow(d, vm) }
                if (s.devices.isEmpty()) item { T(scanStatus, 12f, C.Muted) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PillButton("Rescan", 90.dp, { vm.rescan() })
                BackBtn(onClose)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: DeviceInfo, vm: ControlViewModel) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(d.name, 14f, weight = FontWeight.Medium)
            val routes = d.availableRoutes.joinToString(" · ") { r -> when (r) { RouteKind.LOCAL_UDP -> "WiFi"; RouteKind.BLE -> "BLE"; RouteKind.RELAY_UDP -> "Internet" } }
            T("${d.state.name.lowercase().replaceFirstChar { it.uppercase() }}${if (routes.isNotEmpty()) " · $routes" else ""}${d.lastError?.let { " · $it" } ?: ""}", 11.5f, C.Muted)
        }
        if (d.state == LinkState.ONLINE) PillButton("Disconnect", 96.dp, { vm.disconnect(d.id) })
        else PillButton("Connect", 86.dp, { vm.connect(d.id) }, primary = true)
    }
}

@Composable
private fun ConnectionSheet(vm: ControlViewModel, onClose: () -> Unit) {
    val initial = remember { vm.transportConfig() }
    var service by remember { mutableStateOf(initial["ble_service"].orEmpty()) }
    var command by remember { mutableStateOf(initial["ble_command"].orEmpty()) }
    var events by remember { mutableStateOf(initial["ble_events"].orEmpty()) }
    var prefix by remember { mutableStateOf(initial["ble_prefix"].orEmpty()) }
    var relayHost by remember { mutableStateOf(initial["relay_host"].orEmpty()) }
    var relayPort by remember { mutableStateOf(initial["relay_port"].orEmpty()) }
    var udpAppPort by remember { mutableStateOf(initial["udp_app_port"].orEmpty()) }
    var udpDevicePort by remember { mutableStateOf(initial["udp_device_port"].orEmpty()) }
    var error by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(42.dp, 100.dp).width(314.dp).height(590.dp)
                .clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(24.dp))
                .padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T("Connection configuration", 17f, weight = FontWeight.SemiBold)
            T("BLE Protocol v2 only. UUIDs and advertised-name prefix must match your ESP32 firmware. UDP and relay are not used.", 11.5f, C.Muted)
            T("BLE", 13f, C.Orange, weight = FontWeight.SemiBold)
            ConfigInput("Service UUID", service) { service = it }
            ConfigInput("Command characteristic UUID", command) { command = it }
            ConfigInput("Events characteristic UUID", events) { events = it }
            ConfigInput("Advertised name prefix", prefix) { prefix = it }
            T("Legacy relay fields (unused in v2)", 13f, C.Muted, weight = FontWeight.SemiBold)
            ConfigInput("Relay hostname / IP (blank disables)", relayHost) { relayHost = it }
            ConfigInput("Relay UDP port", relayPort) { relayPort = it }
            T("Legacy UDP fields (unused in v2)", 13f, C.Muted, weight = FontWeight.SemiBold)
            ConfigInput("App listen port", udpAppPort) { udpAppPort = it }
            ConfigInput("ESP device listen port", udpDevicePort) { udpDevicePort = it }
            T("Only BLE GATT settings are active in this protocol version.", 10.5f, C.Muted)
            if (error.isNotBlank()) T(error, 11f, C.Danger)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PillButton("Save", 90.dp, {
                    val ok = vm.saveTransportConfig(mapOf(
                        "ble_service" to service, "ble_command" to command, "ble_events" to events,
                        "ble_prefix" to prefix, "relay_host" to relayHost, "relay_port" to relayPort, "udp_app_port" to udpAppPort, "udp_device_port" to udpDevicePort,
                    ))
                    error = if (ok) "" else "Check UUIDs, prefix and all ports (1–65535)."
                }, primary = true)
                BackBtn(onClose)
            }
        }
    }
}

@Composable
private fun ConfigInput(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        T(label, 10.5f, C.Muted)
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = C.Text, fontSize = androidx.compose.ui.unit.TextUnit(12.5f, androidx.compose.ui.unit.TextUnitType.Sp),
            ),
            modifier = Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF0E0F14)).border(1.dp, C.Line, RoundedCornerShape(9.dp))
                .padding(horizontal = 9.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SecuritySheet(vm: ControlViewModel, onClose: () -> Unit) {
    var key by remember { mutableStateOf(vm.ownerKey()) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(56.dp, 260.dp).width(286.dp)
                .clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T("Owner Key", 17f, weight = FontWeight.SemiBold)
            T("Legacy local setting. BLE Protocol v2 does not transmit or use an Owner Key.", 12.5f, C.Muted)
            androidx.compose.foundation.text.BasicTextField(
                value = key, onValueChange = { key = it },
                textStyle = androidx.compose.ui.text.TextStyle(color = C.Text, fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)),
                modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E0F14)).border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(12.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PillButton("Save", 90.dp, { vm.changeOwnerKey(key) }, primary = true)
                BackBtn(onClose)
            }
        }
    }
}

@Composable
private fun ScrollSheet(s: UiState, vm: ControlViewModel, onClose: () -> Unit) {
    val descr = mapOf(
        ScrollMode.CLASSIC to "Turns exactly as much as you scroll (at 0%).",
        ScrollMode.ACCEL to "Mouse-like: slow swipe turns less, fast swipe turns much more.",
        ScrollMode.FREE to "Keeps spinning after you let go, then slows down and stops.",
    )
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(56.dp, 260.dp).width(286.dp)
                .clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("Scroll", 17f, weight = FontWeight.SemiBold)
            for (m in ScrollMode.values()) {
                val sel = s.mode == m
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF))
                        .border(2.dp, if (sel) C.Orange else C.Line, RoundedCornerShape(14.dp))
                        .clickable { vm.setMode(m) }.padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    T("${m.name.lowercase().replaceFirstChar { it.uppercase() }} · ${s.sens[m] ?: 0}%", 14f, weight = FontWeight.SemiBold)
                    T(descr[m] ?: "", 11.5f, C.Muted, modifier = Modifier.padding(top = 3.dp))
                }
            }
            HSlider(s.sens[s.mode] ?: 0, 100, C.Accent, { vm.setSens(it) }, Modifier.fillMaxWidth())
            T("0% = turns exactly as much as you scroll. 100% = even a small scroll turns a lot.", 11.5f, C.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BackBtn(onClose) }
        }
    }
}


@Composable
private fun EffectsSheet(s: UiState, vm: ControlViewModel, onClose: () -> Unit) {
    val effects = s.devices.filter { it.state == LinkState.ONLINE }
        .flatMap { d -> d.caps?.effects.orEmpty().map { it.name } }
        .distinctBy { it.lowercase() }.sorted()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(42.dp, 150.dp).width(314.dp).height(520.dp)
                .clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T("Effects catalogue", 17f, weight = FontWeight.SemiBold)
            T("Names are discovered from connected ESP32 devices. A selected device runs an effect only when it advertises that name; its local effect ID is resolved independently.", 11.5f, C.Muted)
            T("${s.selRgb.size} RGB candidates selected", 11f, C.Orange, weight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f)) {
                items(effects) { name ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x12FFFFFF))
                            .clickable { vm.triggerEffectByName(name) }.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(name, 13f, weight = FontWeight.Medium)
                        T("RUN", 10f, C.Green, FontWeight.Bold)
                    }
                }
                if (effects.isEmpty()) item {
                    T("No effect catalogue received yet. Connect a device running firmware that advertises its effects in CAPS.", 12f, C.Muted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BackBtn(onClose) }
        }
    }
}

@Composable
private fun FirmwareSheet(s: UiState, onClose: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var deviceName by remember { mutableStateOf("ESP32") }
    var candidates by remember { mutableStateOf(listOf(
        FirmwareCandidate("1","RGB","16","64","RGB Strip"),
        FirmwareCandidate("2","SWITCH","18","0","Relay"),
    )) }
    var generated by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(18.dp, 66.dp).width(324.dp).height(690.dp)
                .clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp,C.Line,RoundedCornerShape(24.dp))
                .padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            T("Firmware generator",17f,weight=FontWeight.SemiBold)
            T("Create candidates one by one. Choose RGB or Switch, assign its GPIO, and set LED count for RGB candidates.",11.5f,C.Muted)
            ConfigInput("ESP32 display name",deviceName){deviceName=it}
            candidates.forEachIndexed { index, candidate ->
                CandidateEditor(candidate,{ updated -> candidates=candidates.mapIndexed { i,v -> if(i==index) updated else v }; generated="" },{
                    if(candidates.size>1) candidates=candidates.filterIndexed { i,_->i!=index }
                    generated=""
                })
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PillButton("Add candidate",130.dp,{
                    candidates=candidates+FirmwareCandidate(
                        id=((candidates.mapNotNull{it.id.toIntOrNull()}.maxOrNull()?:0)+1).toString(),
                        type="RGB",gpio="17",leds="64",name="RGB ${candidates.size+1}"
                    ); generated=""
                })
                PillButton("Generate code",150.dp,{
                    val result=runCatching { FirmwareCodeGenerator.generate(deviceName,candidates) }
                    generated=result.getOrNull().orEmpty()
                    error=result.exceptionOrNull()?.message.orEmpty()
                },primary=true)
            }
            if(error.isNotBlank()) T(error,11f,C.Danger)
            if(generated.isNotBlank()) {
                T("Generated upload-ready sketch",12f,C.Orange,FontWeight.SemiBold)
                T("Copy the complete sketch into Arduino IDE. Install NimBLE-Arduino 2.x and Adafruit NeoPixel, select your ESP32 board, then upload.",11f,C.Muted)
                PillButton("Copy complete firmware code",230.dp,{
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(generated))
                },primary=true)
                T("Firmware source preview",11f,C.Muted)
                T(generated,9.5f,C.Text)
            }
            T("Device candidates and effect catalogue are sent by the firmware through BLE CAPS. The app lists only candidates belonging to currently connected devices.",10.5f,C.Warn)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){BackBtn(onClose)}
        }
    }
}

@Composable
private fun CandidateEditor(candidate: FirmwareCandidate,onChange:(FirmwareCandidate)->Unit,onRemove:()->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x12FFFFFF))
            .border(1.dp,C.Line,RoundedCornerShape(14.dp)).padding(9.dp),
        verticalArrangement=Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            T("Candidate",12f,C.Orange,FontWeight.SemiBold)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF202330)).clickable{expanded=true}.padding(horizontal=12.dp,vertical=7.dp)) {
                    T(candidate.type,11f,weight=FontWeight.SemiBold)
                }
                androidx.compose.material3.DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                    listOf("RGB","SWITCH").forEach { type ->
                        androidx.compose.material3.DropdownMenuItem(text={T(type,13f)},onClick={
                            onChange(candidate.copy(type=type,leds=if(type=="RGB") "64" else "0"));expanded=false
                        })
                    }
                }
                Spacer(Modifier.width(8.dp))
                T("Remove",10.5f,C.Danger,modifier=Modifier.clickable(onClick=onRemove).padding(5.dp))
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)){ConfigInput("Candidate ID",candidate.id){onChange(candidate.copy(id=it))}}
            Box(Modifier.weight(1f)){ConfigInput("GPIO pin",candidate.gpio){onChange(candidate.copy(gpio=it))}}
            if(candidate.type=="RGB") Box(Modifier.weight(1f)){ConfigInput("LED count",candidate.leds){onChange(candidate.copy(leds=it))}}
        }
        ConfigInput("Candidate name",candidate.name){onChange(candidate.copy(name=it))}
    }
}

@Composable
private fun DevicesSheet(s: UiState, vm: ControlViewModel, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(56.dp, 236.dp).width(286.dp).height(520.dp)
                .clip(RoundedCornerShape(24.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(24.dp))
                .padding(16.dp),
        ) {
            T("Devices", 17f, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(s.devices.filter { it.paired }) { d ->
                    DeviceRow(d, vm)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        T("Forget", 12f, C.Danger, modifier = Modifier.clickable { vm.forget(d.id) }.padding(4.dp))
                    }
                }
                if (s.devices.none { it.paired }) item { T("No paired devices yet. Open Scan to add one.", 13f, C.Muted) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BackBtn(onClose) }
        }
    }
}
