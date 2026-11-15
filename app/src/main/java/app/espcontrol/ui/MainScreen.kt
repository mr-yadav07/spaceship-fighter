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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.espcontrol.core.DeviceInfo
import app.espcontrol.core.LinkState
import app.espcontrol.core.RouteKind

private enum class Panel { NONE, SETTINGS, SCAN, RGB, SWITCH, MENU }
private enum class Sheet { NONE, UI, THEME, SWITCH, DEVICES, SECURITY, SCROLL, CONNECTION, EFFECTS, FIRMWARE }

@Composable
fun MainScreen(vm: ControlViewModel) {
    val s by vm.ui.collectAsState()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var settingsSheet by remember { mutableStateOf(Sheet.NONE) }
    var menuSheet by remember { mutableStateOf(Sheet.NONE) }
    var infoOpen by remember { mutableStateOf(false) }
    var revolverUi by remember { mutableStateOf(1) }
    var selectedTheme by remember { mutableStateOf("Dark") }
    var switchPowerUp by remember { mutableStateOf("Last") }
    var switchMode by remember { mutableStateOf("Toggle") }
    val revolverController = remember { RevolverController() }
    val revolverScope = rememberCoroutineScope()

    val eff = Triple(s.effR, s.effG, s.effB)
    val anySelOn = s.rgbPowerOn
    val discShade = 0.75f * (1f - s.br / 255f)
    val connected = s.devices.count { it.state == LinkState.ONLINE }

    val centerRgb = centerItem(s.rgbItems)
    val centerSw = centerItem(s.swItems)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        val canvasScale = minOf(1f, maxWidth / 390.dp, maxHeight / 844.dp)
        Box(
            Modifier.requiredSize(390.dp, 844.dp).graphicsLayer {
                scaleX = canvasScale
                scaleY = canvasScale
            }
                .background(Brush.verticalGradient(when (selectedTheme) { "Light" -> listOf(Color(0xFFE9EBF2), Color(0xFFD4D8E3)); "AMOLED" -> listOf(Color.Black, Color.Black); else -> listOf(C.Bg1, C.Bg2) }))
                .clip(RoundedCornerShape(0.dp)),
        ) {
        Box(Modifier.fillMaxSize()) {
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
            Modifier.offset(102.dp, 312.dp).size(184.dp).shadow(32.dp, CircleShape).alpha(if (anySelOn) 1f else 0.4f),
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

        Column(Modifier.offset(282.dp, (303f + 184f * (1f - s.br / 255f)).dp).width(42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(42.dp).clip(RoundedCornerShape(8.dp)).background(C.Track).padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.Center) { T("${s.br}", 11f, weight = FontWeight.SemiBold) }
        }
        VSlider(s.br, 255, { vm.setBrightness(it) }, Modifier.offset(323.dp, 312.dp).size(14.dp, 184.dp))

        ChannelSlider(116.dp, 520.dp, "R", s.r, s.enR, C.RedCh, { vm.setChannel(0, it) }, { vm.toggleChannel(0) })
        ChannelSlider(116.dp, 562.dp, "G", s.g, s.enG, C.GreenCh, { vm.setChannel(1, it) }, { vm.toggleChannel(1) })
        ChannelSlider(116.dp, 604.dp, "B", s.b, s.enB, C.BlueCh, { vm.setChannel(2, it) }, { vm.toggleChannel(2) })

        ActionRow(18.dp, 676.dp, "RGB", s.rgbSyncActive, s.rgbItems.isNotEmpty() && s.selRgb.size == s.rgbItems.size, { vm.syncRgb() }, { vm.toggleSelectAll(true) }, { vm.togglePower() })
        ActionRow(18.dp, 722.dp, "Switches", s.switchSyncActive, s.swItems.isNotEmpty() && s.selSw.size == s.swItems.size, { vm.syncSwitches(centerSw) }, { vm.toggleSelectAll(false) }, { vm.powerSwitches() })
        }

        if (panel != Panel.NONE || sheet != Sheet.NONE || infoOpen) {
            Box(
                Modifier.fillMaxSize().background(Color(0x8C0A0B10))
                    .clickable { panel = Panel.NONE; sheet = Sheet.NONE; infoOpen = false },
            )
        }

        AnimatedVisibility(panel != Panel.NONE, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            when (panel) {
                Panel.SETTINGS -> SettingsRevolver(vm, s, revolverUi, revolverController, revolverScope) { open -> settingsSheet = open; sheet = open; if (open != Sheet.NONE) panel = Panel.NONE }
                Panel.SCAN -> ScanPanel(s, vm) { panel = Panel.NONE }
                Panel.RGB -> ListRevolver(s.rgbItems, s.selRgb, s.mode, s.sens[s.mode] ?: 0, revolverUi, revolverController, revolverScope, vm::toggleSelect, vm::toggleItemOn)
                Panel.SWITCH -> ListRevolver(s.swItems, s.selSw, s.mode, s.sens[s.mode] ?: 0, revolverUi, revolverController, revolverScope, vm::toggleSelect, vm::toggleItemOn)
                Panel.MENU -> MenuPanel(onClose = { panel = Panel.NONE }, onOpen = { target -> menuSheet = target; panel = Panel.NONE; sheet = target })
                else -> {}
            }
        }
        AnimatedVisibility(infoOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            InfoDetailPlaceholder { infoOpen = false }
        }
        AnimatedVisibility(sheet != Sheet.NONE, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            when (sheet) {
                Sheet.UI -> UiStyleSheet(revolverUi, { revolverUi = it; sheet = Sheet.NONE }, { sheet = Sheet.NONE })
                Sheet.THEME -> ThemeSheet(selectedTheme, { selectedTheme = it; sheet = Sheet.NONE }, { sheet = Sheet.NONE })
                Sheet.SWITCH -> SwitchConfigSheet(s, switchPowerUp, switchMode, { switchPowerUp = it }, { switchMode = it }, { vm.setSelectedSwitches(true) }, { vm.setSelectedSwitches(false) }, { sheet = Sheet.NONE })
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
            onSettings = {
                if (panel == Panel.SETTINGS) panel = Panel.NONE
                else if (settingsSheet != Sheet.NONE) { panel = Panel.NONE; sheet = if (sheet == settingsSheet) Sheet.NONE else settingsSheet }
                else { sheet = Sheet.NONE; panel = Panel.SETTINGS }
            },
            onScan = { sheet = Sheet.NONE; panel = if (panel == Panel.SCAN) Panel.NONE else Panel.SCAN },
            onRgb = { sheet = Sheet.NONE; panel = if (panel == Panel.RGB) Panel.NONE else Panel.RGB },
            onSwitch = { sheet = Sheet.NONE; panel = if (panel == Panel.SWITCH) Panel.NONE else Panel.SWITCH },
            onMenu = {
                if (panel == Panel.MENU) panel = Panel.NONE
                else if (menuSheet != Sheet.NONE) { panel = Panel.NONE; sheet = if (sheet == menuSheet) Sheet.NONE else menuSheet }
                else { sheet = Sheet.NONE; panel = Panel.MENU }
            },
        )
        }
    }
}

/** No rotation tracked here (that lives inside Revolver); Sync targets the first selected switch, or the first switch. */
private fun centerItem(items: List<OutItem>): OutItem? = items.firstOrNull { it.on } ?: items.firstOrNull()

@Composable
private fun InfoCard(s: UiState, connected: Int, centerRgb: OutItem?, centerSw: OutItem?, onOpen: () -> Unit) {
    Column(
        Modifier.offset(58.dp, 48.dp).width(274.dp).height(236.dp)
            .shadow(28.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0x12FFFFFF), Color(0x06FFFFFF))))
            .border(1.dp, C.Line, RoundedCornerShape(28.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("INFORMATION", 11f, C.Muted, FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (s.demo) C.Orange else C.Green))
                T(if (s.demo) "DEMO" else "LIVE", 11f, if (s.demo) C.Orange else C.Green, FontWeight.SemiBold)
            }
        }
        InfoRow("Connected", "$connected devices")
        InfoRow("RGB", "${s.selRgb.size}/${s.rgbItems.size} sel · ${s.rgbItems.count { it.on }} on")
        InfoRow("Switches", "${s.selSw.size}/${s.swItems.size} sel · ${s.swItems.count { it.on }} on")
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Colour", 12.5f, C.Muted, modifier = Modifier.weight(1f))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(if (s.enR) s.r else 0, if (s.enG) s.g else 0, if (s.enB) s.b else 0)))
                T("R ${if (s.enR) s.r else "–"} · G ${if (s.enG) s.g else "–"} · B ${if (s.enB) s.b else "–"}", 11.5f, C.Text)
            }
            T("›", 17f, C.Muted, modifier = Modifier.width(18.dp))
        }
        InfoRow("Brightness", "${(s.br * 100 / 255)}%")
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x18FFFFFF)))
        T(s.msg, 12f, C.Warn, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 12.5f, C.Muted, modifier = Modifier.weight(1f))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { T(value, 12.5f, C.Text) }
        T("›", 17f, C.Muted, modifier = Modifier.width(18.dp))
    }
}

@Composable
private fun ChannelSlider(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, letter: String, value: Int, on: Boolean, color: Color, onChange: (Int) -> Unit, onToggle: () -> Unit) {
    HSlider(value, 255, if (on) color else C.Off, onChange, Modifier.offset(x, y).width(212.dp))
    ChannelButton(letter, on, color, if (letter == "B") Color.White else Color(0xFF0E0F14), onToggle, Modifier.offset(344.dp, y + 16.dp))
}

@Composable
private fun ActionRow(
    x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, label: String,
    syncActive: Boolean, allSelected: Boolean,
    onSync: () -> Unit, onSelectAll: () -> Unit, onPower: () -> Unit,
) {
    Row(Modifier.offset(x, y).fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        T(label, 11f, C.Muted, modifier = Modifier.width(52.dp))
        Spacer(Modifier.width(6.dp))
        PillButton(if (syncActive) "Restore" else "Sync", 68.dp, onSync, primary = syncActive)
        Spacer(Modifier.width(6.dp))
        PillButton(if (allSelected) "Deselect All" else "Select All", 92.dp, onSelectAll)
        Spacer(Modifier.width(6.dp))
        PillButton("On/Off", 60.dp, onPower)
        Spacer(Modifier.width(34.dp))
    }
}

@Composable
private fun SideColumn(
    panel: Panel, onSettings: () -> Unit, onScan: () -> Unit,
    onRgb: () -> Unit, onSwitch: () -> Unit, onMenu: () -> Unit,
) {
    Column(Modifier.offset(344.dp, 262.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.End) {
        SideBtn(panel == Panel.MENU, onMenu) { MenuIcon() }
        SideBtn(panel == Panel.SCAN, onScan) { T("Scan", 11f) }
        SideBtn(panel == Panel.SETTINGS, onSettings) { GearIcon() }
        SideBtn(panel == Panel.RGB, onRgb) { T("RGB", 11f) }
        SideBtn(panel == Panel.SWITCH, onSwitch) { T("0/1", 11f) }
    }
}

@Composable
private fun SideBtn(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(46.dp, 46.dp)
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
private fun MenuPanel(onClose: () -> Unit, onOpen: (Sheet) -> Unit) {
    val entries = listOf(
        "Connections" to Sheet.DEVICES,
        "Firmware configuration" to Sheet.FIRMWARE,
        "Connection configuration" to Sheet.CONNECTION,
        "Security / Owner Key" to Sheet.SECURITY,
        "Effects catalogue" to Sheet.EFFECTS,
    )
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(112.dp, 264.dp).width(230.dp)
                .clip(RoundedCornerShape(22.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(22.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entries.forEach { (label, target) ->
                Box(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp))
                    .clickable { onOpen(target) }.padding(start = 14.dp), contentAlignment = Alignment.CenterStart) {
                    T(label, 13f)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BackBtn(onClose) }
        }
    }
}

@Composable
private fun ListRevolver(
    items: List<OutItem>, selected: Set<String>, mode: ScrollMode, sens: Int, ui: Int,
    controller: RevolverController, motionScope: kotlinx.coroutines.CoroutineScope,
    onSelect: (OutItem) -> Unit, onToggle: (OutItem) -> Unit,
) {
    Revolver(
        items = items.map { RevItem(it.label, sel = it.key in selected, on = it.on, dot = if (it.isRgb) it.color else null) },
        mode = mode, sens = sens, ui = ui, controller = controller, motionScope = motionScope,
        onName = { i -> items.getOrNull(i)?.let(onSelect) },
        onToggle = { i -> items.getOrNull(i)?.let(onToggle) },
    )
}

@Composable
private fun SettingsRevolver(vm: ControlViewModel, s: UiState, ui: Int, controller: RevolverController, motionScope: kotlinx.coroutines.CoroutineScope, openSheet: (Sheet) -> Unit) {
    val items = listOf(
        "Devices" to Sheet.DEVICES,
        "Effects catalogue" to Sheet.EFFECTS,
        "Firmware / GPIO setup" to Sheet.FIRMWARE,
        "Connection config" to Sheet.CONNECTION,
        "Security (Owner Key)" to Sheet.SECURITY,
        "Scroll" to Sheet.SCROLL,
        "UI style" to Sheet.UI,
        "Theme" to Sheet.THEME,
        "Switch config" to Sheet.SWITCH,
    )
    Revolver(
        items = items.map { (name, _) -> RevItem(name, sel = false, on = false, dot = null) } +
            RevItem("Demo data", sel = false, on = s.demo, dot = null),
        mode = s.mode, sens = s.sens[s.mode] ?: 0, ui = ui, finite = true, controller = controller, motionScope = motionScope,
        onName = { i ->
            if (i == items.size) vm.setDemo(!s.demo)
            else items.getOrNull(i)?.second?.let(openSheet)
        },
        onToggle = { i -> if (i == items.size) vm.setDemo(!s.demo) },
    )
}

@Composable
private fun UiStyleSheet(selected: Int, onSelect: (Int) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(56.dp, 236.dp).width(286.dp)
                .clip(RoundedCornerShape(24.dp)).background(Color(0xE612141C))
                .border(1.dp, C.Line, RoundedCornerShape(24.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("Choose revolver style", 12f, C.Muted)
            listOf(
                1 to ("UI 1" to "Oval revolver: tall list, more devices at a time"),
                2 to ("UI 2" to "Hub half-circle revolver: centre is big, edges are small"),
            ).forEach { (id, entry) ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (selected == id) Color(0xFF1E2740) else Color(0xFF0E0F14))
                        .border(2.dp, if (selected == id) C.Orange else C.Line, RoundedCornerShape(16.dp))
                        .clickable { onSelect(id) }.padding(14.dp),
                ) {
                    T(entry.first, 15f, weight = FontWeight.SemiBold)
                    T(entry.second, 12f, C.Muted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BackBtn(onClose) }
        }
    }
}


@Composable
private fun ThemeSheet(selected: String, onSelect: (String) -> Unit, onClose: () -> Unit) {
    val themes = listOf("Dark" to Color(0xFF0E0F14), "Light" to Color(0xFFF2F3F7), "AMOLED" to Color.Black)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(56.dp, 236.dp).width(286.dp).height(340.dp)
                .clip(RoundedCornerShape(24.dp)).background(Color(0xE612141C))
                .border(1.dp, C.Line, RoundedCornerShape(24.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("Theme", 17f, weight = FontWeight.SemiBold)
            themes.forEach { (name, swatch) ->
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (selected == name) Color(0xFF1E2740) else Color(0xFF0E0F14))
                        .border(2.dp, if (selected == name) C.Accent else C.Line, RoundedCornerShape(14.dp))
                        .clickable { onSelect(name) }.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(name, 15f, weight = FontWeight.Medium)
                    Box(Modifier.size(26.dp).clip(CircleShape).background(swatch).border(1.dp, C.Muted, CircleShape))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BackBtn(onClose) }
        }
    }
}

@Composable
private fun SwitchConfigSheet(
    s: UiState, powerUp: String, mode: String, onPowerUp: (String) -> Unit, onMode: (String) -> Unit,
    onSelectedOn: () -> Unit, onSelectedOff: () -> Unit, onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(56.dp, 236.dp).width(286.dp).height(340.dp)
                .clip(RoundedCornerShape(24.dp)).background(Color(0xE612141C))
                .border(1.dp, C.Line, RoundedCornerShape(24.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T("Switch", 17f, weight = FontWeight.SemiBold)
            T("Applies to selected switches (${s.selSw.size})", 12f, C.Muted)
            T("Power-up state", 13f, weight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Last", "ON", "OFF").forEach { item ->
                    Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (powerUp == item) Color(0xFF1E2740) else C.Bg1)
                        .border(1.dp, if (powerUp == item) C.Accent else C.Line, RoundedCornerShape(12.dp))
                        .clickable { onPowerUp(item) }, contentAlignment = Alignment.Center) { T(item, 12f) }
                }
            }
            T("Mode", 13f, weight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Toggle", "Momentary").forEach { item ->
                    Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (mode == item) Color(0xFF1E2740) else C.Bg1)
                        .border(1.dp, if (mode == item) C.Accent else C.Line, RoundedCornerShape(12.dp))
                        .clickable { onMode(item) }, contentAlignment = Alignment.Center) { T(item, 12f) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Selected ON", 120.dp, onSelectedOn, primary = true)
                PillButton("Selected OFF", 120.dp, onSelectedOff)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BackBtn(onClose) }
        }
    }
}

@Composable
private fun ScanPanel(s: UiState, vm: ControlViewModel, onClose: () -> Unit) {
    val scanStatus by vm.scanStatus.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.offset(84.dp, (780 - (s.devices.size * 56 + 92)).coerceAtMost(190).coerceAtLeast(150).dp).width(258.dp)
                .clip(RoundedCornerShape(26.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(26.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            T(if (s.demo) "Devices · DEMO DATA ON" else "Devices · BLE", 16f, weight = FontWeight.SemiBold)
            LazyColumn(Modifier.height((s.devices.size * 56).coerceAtLeast(56).dp)) {
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
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
        FirmwareCandidate("1","RGB","18","64","RGB Strip"),
        FirmwareCandidate("2","SWITCH","2","0","Relay"),
        FirmwareCandidate("3","RGB","5","64","RGB 3"),
    )) }
    var enabledEffects by remember { mutableStateOf(setOf(1, 2, 3, 4)) }
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
            ConfigInput("ESP32 display name",deviceName){deviceName=it; generated=""}
            T("Preset effects included in this firmware",11.5f,C.Muted,FontWeight.SemiBold)
            listOf(1 to "Rainbow", 2 to "Pulse", 3 to "Color Cycle", 4 to "Chase").forEach { (id, name) ->
                PillButton(if (id in enabledEffects) "✓ $name" else "＋ $name", 138.dp, {
                    enabledEffects = if (id in enabledEffects) enabledEffects - id else enabledEffects + id
                    generated = ""
                }, primary = id in enabledEffects)
            }
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
                    val result=runCatching { FirmwareCodeGenerator.generate(deviceName,candidates,enabledEffects) }
                    generated=result.getOrNull().orEmpty()
                    error=result.exceptionOrNull()?.message.orEmpty()
                },primary=true)
            }
            if(error.isNotBlank()) T(error,11f,C.Danger)
            if(generated.isNotBlank()) {
                T("Generated upload-ready sketch",12f,C.Orange,FontWeight.SemiBold)
                T("Use the Copy button for the complete source. Install NimBLE-Arduino 2.x and Adafruit NeoPixel, select ESP32 Dev Module, then upload.",11f,C.Muted)
                PillButton("Copy complete firmware code",230.dp,{
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(generated))
                },primary=true)
                T("Firmware source preview · select text if needed",11f,C.Muted)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    androidx.compose.material3.Text(
                        text = generated,
                        color = C.Text,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = androidx.compose.ui.unit.TextUnit(9.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        modifier = Modifier.fillMaxWidth().height(240.dp).verticalScroll(rememberScrollState()),
                    )
                }
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
