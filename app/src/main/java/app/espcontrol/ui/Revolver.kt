package app.espcontrol.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class RevItem(val name: String, val sel: Boolean, val on: Boolean, val dot: Color?)

class RevolverController {
    val rotation = mutableFloatStateOf(0f)
    var motion: Job? = null
}

/** Visible left half of an oval that is twice as wide as the box (the right half is off-screen). */


private class Slot(val i: Int, val vis: Boolean, val scale: Float, val op: Float, val left: Float, val top: Float)

// Geometry copied from Main v2 HTML artboard (390 x 844).
private const val CY = 407f
private data class RevGeometry(
    val r: Float, val b: Float, val step: Float, val w: Float, val h: Float,
    val circle: Float, val pill: Float, val font: Float, val panLeft: Float,
    val panTop: Float, val panWidth: Float, val panHeight: Float,
) {
    val spacing = b * step
}
private fun geometry(ui: Int, finite: Boolean): RevGeometry = if (ui == 2) {
    RevGeometry(90f, 90f, if (finite) 0.5f else 0.44f, 124f, 38f, 30f, 88f, 12f, 160f, 252f, 460f, 310f)
} else {
    RevGeometry(92f, 260f, if (finite) 0.32f else 0.185f, 142f, 40f, 34f, 100f, 13f, 140f, 122f, 500f, 570f)
}
private fun slots(n: Int, rot: Float, ui: Int, finite: Boolean): List<Slot> {
    if (n <= 0) return emptyList()
    val g = geometry(ui, finite)
    val range = if (ui == 2) 4 else 7
    // Every revolver is cyclic: no first/last-item boundary, including the settings menu.
    val first = rot.toInt() - range
    val last = rot.toInt() + range
    return (first..last).mapNotNull { virtualIndex ->
        val itemIndex = ((virtualIndex % n) + n) % n
        val d = virtualIndex - rot
        val th = d * g.step
        val c = cos(th)
        val ad = abs(d)
        val v = when {
            ad <= 1f -> { val t = ad; (1f - 0.06f * t) to (1f - 0.05f * t) }
            ad <= 2f -> { val t = ad - 1f; (0.94f + (0.62f - 0.94f) * t) to (0.95f + (0.8f - 0.95f) * t) }
            ad <= 3f -> { val t = ad - 2f; (0.62f + (0.46f - 0.62f) * t) to (0.8f + (0.62f - 0.8f) * t) }
            ad < 3.7f -> { val t = (ad - 3f) / 0.7f; (0.46f + (0.38f - 0.46f) * t) to (0.62f * (1f - t)) }
            else -> 0.38f to 0f
        }
        val visible = if (ui == 2) ad < 3.7f && c > 0.05f else c > 0.3f
        if (!visible) null else {
            val scale = if (ui == 2) v.first else 0.55f + 0.6f * max(c, 0f).pow(2.5f)
            val opacity = if (ui == 2) v.second else min(1f, (c - 0.3f) * 4f)
            Slot(itemIndex, true, scale, opacity, 390f - g.r * c - g.w, CY + g.b * sin(th) - g.h / 2f)
        }
    }
}
/**
 * Oval revolver for RGB / switch lists. Every visible item is tappable (name = select, circle = ON/OFF).
 * Scroll modes: CLASSIC (1:1, with gain), ACCEL (mouse-like speed gain), FREE (keeps spinning after release).
 */
@Composable
fun Revolver(
    items: List<RevItem>, mode: ScrollMode, sens: Int, ui: Int = 1, finite: Boolean = false,
    controller: RevolverController, motionScope: CoroutineScope,
    onName: (Int) -> Unit, onToggle: (Int) -> Unit,
) {
    val n = items.size
    val g = geometry(ui, finite)
    val density = LocalDensity.current.density
    var rot by controller.rotation
    val curMode by rememberUpdatedState(mode)
    val curSens by rememberUpdatedState(sens)
    val curN by rememberUpdatedState(n)
    val curUi by rememberUpdatedState(ui)
    val curFinite by rememberUpdatedState(finite)
    val nameCb by rememberUpdatedState(onName)
    val toggleCb by rememberUpdatedState(onToggle)

    fun stepBy(delta: Float) {
        controller.motion?.cancel()
        controller.motion = motionScope.launch {
            val target = rot + delta
            animate(initialValue = rot, targetValue = target, animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { v, _ -> rot = v }
        }
    }

    fun fling(v0: Float, sensF: Float) {
        controller.motion?.cancel()
        controller.motion = motionScope.launch {
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
            // Inertia naturally decays at the position reached; no forced snap.
        }
    }

    fun tap(xDp: Float, yDp: Float) {
        val gg = geometry(curUi, curFinite)
        val x = gg.panLeft + xDp
        val y = gg.panTop + yDp
        var best: Slot? = null
        var circle = false
        for (s in slots(curN, rot, curUi, curFinite)) {
            if (!s.vis) continue
            val w = gg.w * s.scale
            val h = gg.h * s.scale
            val right = s.left + gg.w
            val mid = s.top + gg.h / 2f
            if (x >= right - w - 3f && x <= right + 3f && y >= mid - h / 2f && y <= mid + h / 2f) {
                if (best == null || s.scale > best.scale) {
                    best = s
                    circle = !curFinite && x > right - gg.circle * s.scale - 2f
                }
            }
        }
        val b = best ?: return
        if (circle) toggleCb(b.i) else nameCb(b.i)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.offset(g.panLeft.dp, g.panTop.dp).size(g.panWidth.dp, g.panHeight.dp)
                .clip(CircleShape).background(Color(0x8C12141C))
                            .pointerInput(ui, finite) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    controller.motion?.cancel()
                    val vt = VelocityTracker()
                    vt.addPosition(down.uptimeMillis, down.position)
                    var lastY = down.position.y
                    var lastT = down.uptimeMillis
                    var speed = 0f
                    var first = true
                    var dragging = false
                    var freeVelocity = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (!dragging) {
                                tap(down.position.x / density, down.position.y / density)
                            } else if (curMode == ScrollMode.FREE && curSens > 0) {
                                val s = curSens / 100f
                                val tracked = -(vt.calculateVelocity().y / density / 1000f) / g.spacing
                                val v = (if (abs(tracked) > 0.001f) tracked else freeVelocity) *
                                    (0.7f + s * 1.1f)
                                val bounded = v.coerceIn(-0.075f, 0.075f)
                                if (abs(bounded) > 0.00025f) fling(bounded, s)
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
                            val deltaItems = -dyDp * gain / g.spacing
                            rot += deltaItems
                            if (curMode == ScrollMode.FREE) {
                                val instant = deltaItems / dt
                                freeVelocity = if (first) instant else freeVelocity * 0.55f + instant * 0.45f
                            }
                            ch.consume()
                        }
                        lastY = ch.position.y
                        lastT = ch.uptimeMillis
                    }
                }
            },
        )
        for (s in slots(n, rot, ui, finite)) {
            if (!s.vis) continue
            val item = items.getOrNull(s.i) ?: continue
            Row(
                Modifier.offset(s.left.dp, s.top.dp).size(g.w.dp, g.h.dp)
                    .graphicsLayer { scaleX = s.scale; scaleY = s.scale; alpha = s.op; transformOrigin = TransformOrigin(1f, 0.5f) },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier.width((if (finite) g.w else g.pill).dp).height(g.h.dp).clip(shape).background(C.Card)
                        .border(2.dp, if (item.sel) C.Orange else C.Accent, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(if (item.on) item.dot else C.Off))
                        T(item.name, g.font, C.Text, FontWeight.Medium)
                    }
                }
                if (!finite) {
                    Box(
                        Modifier.size(g.circle.dp).clip(CircleShape).background(if (item.on) C.Green else C.Track),
                        contentAlignment = Alignment.Center,
                    ) { T(if (item.on) "ON" else "OFF", 11f, if (item.on) Color(0xFF0E0F14) else C.Muted, FontWeight.SemiBold) }
                }
            }
        }
    }
}
