package app.espcontrol.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

data class RevItem(val name: String, val sel: Boolean, val on: Boolean, val dot: Color?)

/** Visible left half of an oval that is twice as wide as the box (the right half is off-screen). */


private class Slot(val i: Int, val vis: Boolean, val scale: Float, val op: Float, val left: Float, val top: Float)

// Geometry of the oval ("egg") revolver in design dp (390 x 844). Same numbers as the Main v2 mockup.
private const val R = 92f
private const val B = 260f
private const val STEP = 0.185f
private const val CY = 407f
private const val W = 142f
private const val H = 40f
private const val PL = 140f
private const val PT = 122f
private const val PH = 570f
private const val SPACING = B * STEP

private fun slots(n: Int, rot: Float): List<Slot> = (0 until n).map { i ->
    var d = (((i - rot) % n) + n) % n
    if (d > n / 2f) d -= n
    val th = d * STEP
    val c = cos(th)
    val vis = c > 0.3f
    Slot(
        i, vis, 0.55f + 0.6f * max(c, 0f).pow(2.5f), min(1f, (c - 0.3f) * 4f),
        390f - R * c - W, CY + B * sin(th) - H / 2f,
    )
}

/**
 * Oval revolver for RGB / switch lists. Every visible item is tappable (name = select, circle = ON/OFF).
 * Scroll modes: CLASSIC (1:1, with gain), ACCEL (mouse-like speed gain), FREE (keeps spinning after release).
 */
@Composable
fun Revolver(
    items: List<RevItem>, mode: ScrollMode, sens: Int,
    onName: (Int) -> Unit, onToggle: (Int) -> Unit,
) {
    val n = items.size
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var rot by remember { mutableFloatStateOf(0f) }
    var motion by remember { mutableStateOf<Job?>(null) }
    val curMode by rememberUpdatedState(mode)
    val curSens by rememberUpdatedState(sens)
    val curN by rememberUpdatedState(n)
    val nameCb by rememberUpdatedState(onName)
    val toggleCb by rememberUpdatedState(onToggle)

    fun snap() {
        motion?.cancel()
        motion = scope.launch {
            val target = rot.roundToInt().toFloat()
            animate(initialValue = rot, targetValue = target, animationSpec = tween(280)) { v, _ -> rot = v }
        }
    }

    fun fling(v0: Float, sensF: Float) {
        motion?.cancel()
        motion = scope.launch {
            var vel = v0
            val tau = 300f + sensF * 1100f
            var last = withFrameNanos { it }
            while (abs(vel) >= 0.0005f) {
                val now = withFrameNanos { it }
                val dt = min(32f, (now - last) / 1_000_000f)
                last = now
                rot += vel * dt
                vel *= exp(-dt / tau)
            }
            val target = rot.roundToInt().toFloat()
            animate(initialValue = rot, targetValue = target, animationSpec = tween(280)) { v, _ -> rot = v }
        }
    }

    fun tap(xDp: Float, yDp: Float) {
        val x = PL + xDp
        val y = PT + yDp
        var best: Slot? = null
        var circle = false
        for (s in slots(curN, rot)) {
            if (!s.vis) continue
            val w = W * s.scale
            val h = H * s.scale
            val right = s.left + W
            val mid = s.top + H / 2f
            if (x >= right - w - 3f && x <= right + 3f && y >= mid - h / 2f && y <= mid + h / 2f) {
                if (best == null || s.scale > best.scale) {
                    best = s
                    circle = x > right - 36f * s.scale - 2f
                }
            }
        }
        val b = best ?: return
        if (circle) toggleCb(b.i) else nameCb(b.i)
    }

    Box(
        Modifier.offset(PL.dp, PT.dp).size(500.dp, PH.dp)
            .clip(CircleShape).background(Color(0x8C12141C)).border(1.dp, C.Line, CircleShape)
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    motion?.cancel()
                    val vt = VelocityTracker()
                    vt.addPosition(down.uptimeMillis, down.position)
                    var lastY = down.position.y
                    var lastT = down.uptimeMillis
                    var speed = 0f
                    var first = true
                    var dragging = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (!dragging) {
                                tap(down.position.x / density, down.position.y / density)
                            } else if (curMode == ScrollMode.FREE && curSens > 0) {
                                val s = curSens / 100f
                                val vy = vt.calculateVelocity().y
                                val v = (-(vy / density / 1000f) / SPACING * (0.35f + s * 0.9f)).coerceIn(-0.05f, 0.05f)
                                if (abs(v) > 0.0004f) fling(v, s) else snap()
                            } else {
                                snap()
                            }
                            break
                        }
                        vt.addPosition(ch.uptimeMillis, ch.position)
                        if (!dragging && abs(ch.position.y - down.position.y) > slop) dragging = true
                        if (dragging) {
                            val dyDp = (ch.position.y - lastY) / density
                            val dt = max(4L, ch.uptimeMillis - lastT).toFloat()
                            val s = curSens / 100f
                            val gain = when (curMode) {
                                ScrollMode.CLASSIC -> 1f + s * 5f
                                ScrollMode.ACCEL -> {
                                    val sp = abs(dyDp) / dt
                                    speed = if (first) sp else speed * 0.6f + sp * 0.4f
                                    first = false
                                    1f + s * min(speed, 5f) * 4f
                                }
                                ScrollMode.FREE -> 1f + s * 2f
                            }
                            rot += -dyDp * gain / SPACING
                            ch.consume()
                        }
                        lastY = ch.position.y
                        lastT = ch.uptimeMillis
                    }
                }
            },
    ) {
        Box(Modifier.offset((298f - PL).dp, 25.dp).size(184.dp, 520.dp).border(1.dp, Color(0x24FFFFFF), CircleShape))
        for (s in slots(n, rot)) {
            if (!s.vis) continue
            val item = items.getOrNull(s.i) ?: continue
            Row(
                Modifier.offset((s.left - PL).dp, (s.top - PT).dp).size(W.dp, H.dp)
                    .graphicsLayer { scaleX = s.scale; scaleY = s.scale; alpha = s.op; transformOrigin = TransformOrigin(1f, 0.5f) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier.width(100.dp).height(H.dp).clip(shape).background(C.Card)
                        .border(2.dp, if (item.sel) C.Orange else C.Accent, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(if (item.on) item.dot else C.Off))
                        T(item.name, 13f, C.Text, FontWeight.Medium)
                    }
                }
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(if (item.on) C.Green else C.Track),
                    contentAlignment = Alignment.Center,
                ) { T(if (item.on) "ON" else "OFF", 11f, if (item.on) Color(0xFF0E0F14) else C.Muted, FontWeight.SemiBold) }
            }
        }
    }
}
