package app.espcontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

fun Modifier.glass(shape: Shape, fill: Color = C.Glass): Modifier = this.background(fill, shape).border(1.dp, C.Line, shape)

@Composable
fun T(
    text: String, size: Float = 13f, color: Color = C.Text, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier, maxLines: Int = 1,
) {
    Text(
        text = text, modifier = modifier, color = color, fontSize = size.sp, fontWeight = weight,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

// ------------------------------------------------------------------ colour maths

fun hsToRgb(hue: Float, sat: Float): Triple<Int, Int, Int> {
    val hh = hue / 60f
    val c = sat
    val xx = c * (1f - abs(hh % 2f - 1f))
    val m = 1f - c
    val (r, g, b) = when {
        hh < 1 -> Triple(c, xx, 0f)
        hh < 2 -> Triple(xx, c, 0f)
        hh < 3 -> Triple(0f, c, xx)
        hh < 4 -> Triple(0f, xx, c)
        hh < 5 -> Triple(xx, 0f, c)
        else -> Triple(c, 0f, xx)
    }
    return Triple(((r + m) * 255f).roundToInt().coerceIn(0, 255), ((g + m) * 255f).roundToInt().coerceIn(0, 255), ((b + m) * 255f).roundToInt().coerceIn(0, 255))
}

/** Returns (hue 0..360, saturation 0..1) of an RGB triple. */
fun rgbToHs(r: Int, g: Int, b: Int): Pair<Float, Float> {
    val mx = maxOf(r, g, b).toFloat()
    val mn = minOf(r, g, b).toFloat()
    val d = mx - mn
    var h = 0f
    if (d > 0f) {
        h = when (mx) {
            r.toFloat() -> ((g - b) / d) % 6f
            g.toFloat() -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        if (h < 0f) h += 360f
    }
    return h to (if (mx == 0f) 0f else d / mx)
}

private fun discRgb(p: Offset, s: IntSize): Triple<Int, Int, Int> {
    val rr = s.width / 2f
    val x = p.x - rr
    val y = p.y - rr
    var hue = Math.toDegrees(atan2(x, -y).toDouble()).toFloat()
    if (hue < 0f) hue += 360f
    val sat = min(1f, sqrt(x * x + y * y) / (0.68f * rr))
    return hsToRgb(hue, sat)
}

// ------------------------------------------------------------------ rainbow disc (tap OR slide)

@Composable
fun ColorDisc(modifier: Modifier, eff: Triple<Int, Int, Int>, shade: Float, onPick: (Int, Int, Int) -> Unit) {
    val pick by rememberUpdatedState(onPick)
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val s = size
                discRgb(down.position, s).let { pick(it.first, it.second, it.third) }
                drag(down.id) { ch ->
                    discRgb(ch.position, s).let { pick(it.first, it.second, it.third) }
                    ch.consume()
                }
            }
        },
    ) {
        val r = size.minDimension / 2f
        rotate(-90f) {
            drawCircle(
                Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
                radius = r,
            )
        }
        drawCircle(
            Brush.radialGradient(0f to Color.White, 0.68f to Color.Transparent, 1f to Color.Transparent, center = center, radius = r),
            radius = r,
        )
        drawCircle(Color.Black.copy(alpha = shade.coerceIn(0f, 1f)), radius = r)
        val (h, sat) = rgbToHs(eff.first, eff.second, eff.third)
        val rad = h * PI.toFloat() / 180f
        val dist = sat * 0.68f * r
        val c = Offset(center.x + dist * sin(rad), center.y - dist * cos(rad))
        drawCircle(Color(eff.first, eff.second, eff.third), radius = 10.dp.toPx(), center = c)
        drawCircle(Color.White, radius = 10.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
    }
}

// ------------------------------------------------------------------ sliders

@Composable
fun HSlider(
    value: Int, max: Int, fill: Color, onChange: (Int) -> Unit,
    modifier: Modifier = Modifier, showBadge: Boolean = true,
) {
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(modifier.height(42.dp)) {
        val frac = (value.toFloat() / max).coerceIn(0f, 1f)
        Canvas(
            Modifier.matchParentSize().pointerInput(max) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    change((down.position.x / w * max).coerceIn(0f, max.toFloat()).roundToInt())
                    drag(down.id) { ch ->
                        change((ch.position.x / w * max).coerceIn(0f, max.toFloat()).roundToInt())
                        ch.consume()
                    }
                }
            },
        ) {
            val th = 14.dp.toPx()
            val top = 24.dp.toPx()
            drawRoundRect(C.Track, Offset(0f, top), Size(size.width, th), CornerRadius(th / 2))
            drawRoundRect(fill, Offset(0f, top), Size(size.width * frac, th), CornerRadius(th / 2))
            drawCircle(Color.White, 14.dp.toPx(), Offset(size.width * frac, top + th / 2))
        }
        if (showBadge) {
            Box(
                Modifier.offset(x = maxWidth * frac - 16.dp, y = 0.dp).clip(RoundedCornerShape(8.dp)).background(C.Track),
            ) { T("$value", 11f, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)) }
        }
    }
}

@Composable
fun VSlider(value: Int, max: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier.pointerInput(max) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val h = size.height.toFloat()
                change(((1f - down.position.y / h) * max).coerceIn(0f, max.toFloat()).roundToInt())
                drag(down.id) { ch ->
                    change(((1f - ch.position.y / h) * max).coerceIn(0f, max.toFloat()).roundToInt())
                    ch.consume()
                }
            }
        },
    ) {
        val tw = 14.dp.toPx()
        val x = (size.width - tw) / 2f
        val frac = (value.toFloat() / max).coerceIn(0f, 1f)
        drawRoundRect(C.Track, Offset(x, 0f), Size(tw, size.height), CornerRadius(tw / 2))
        drawRoundRect(
            Brush.verticalGradient(listOf(C.Text, C.Muted)),
            Offset(x, size.height * (1f - frac)), Size(tw, size.height * frac), CornerRadius(tw / 2),
        )
        drawCircle(Color.White, 14.dp.toPx(), Offset(size.width / 2f, size.height * (1f - frac)))
    }
}

// ------------------------------------------------------------------ small buttons and icons

@Composable
fun ChannelButton(letter: String, on: Boolean, color: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(30.dp).clip(CircleShape)
            .background(if (on) color else Color.Transparent)
            .border(2.dp, if (on) color else Color(0x38FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(letter, 12f, if (on) fg else C.Muted, FontWeight.Bold) }
}

@Composable
fun PillButton(label: String, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Box(
        modifier.width(width).height(38.dp).clip(RoundedCornerShape(12.dp))
            .background(if (primary) C.Accent else Color(0x0FFFFFFF))
            .border(1.dp, if (primary) C.Accent else Color(0x24FFFFFF), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(label, 12f, if (primary) Color(0xFF0E0F14) else C.Text, FontWeight.Medium) }
}

@Composable
fun BackBtn(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(46.dp).clip(CircleShape).background(Color(0x14FFFFFF)).border(1.dp, Color(0x2EFFFFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val s = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round)
            val w = size.width
            drawLine(C.Text, Offset(w * 0.62f, w * 0.2f), Offset(w * 0.34f, w * 0.5f), s.width, StrokeCap.Round)
            drawLine(C.Text, Offset(w * 0.34f, w * 0.5f), Offset(w * 0.62f, w * 0.8f), s.width, StrokeCap.Round)
        }
    }
}

@Composable
fun GearIcon(tint: Color = C.Text) {
    Canvas(Modifier.size(22.dp)) {
        val c = center
        val r = size.minDimension * 0.28f
        drawCircle(tint, r, c, style = Stroke(1.8.dp.toPx()))
        drawCircle(tint, r * 0.4f, c, style = Stroke(1.8.dp.toPx()))
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4f
            drawLine(tint, Offset(c.x + r * 1.15f * sin(a), c.y - r * 1.15f * cos(a)), Offset(c.x + r * 1.7f * sin(a), c.y - r * 1.7f * cos(a)), 2.6.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
fun MenuIcon(tint: Color = C.Text) {
    Canvas(Modifier.size(20.dp)) {
        for (i in 0 until 3) {
            val y = size.height * (0.25f + 0.25f * i)
            drawLine(tint, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), 1.8.dp.toPx(), StrokeCap.Round)
        }
    }
}
