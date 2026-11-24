package forma.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.theme.F
import kotlin.math.max
import kotlin.math.min

/** A data series for [LineChart]. */
data class Series(val values: List<Double>, val color: Color, val fill: Boolean = false, val width: Dp = 2.5.dp)

/**
 * Line chart on one shared y-axis. [xLabels] are spread evenly under the plot.
 * [reference] draws a dashed-free thin line at that value (e.g. 100 for an index, 0 for form).
 */
@Composable
fun LineChart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    xLabels: List<String> = emptyList(),
    reference: Double? = null,
    description: String,
    yMin: Double? = null,
) {
    val grid = F.c.line
    val labelColor = F.c.text3
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = F.type.text, fontSize = 10.sp, color = labelColor)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val all = series.flatMap { it.values } + listOfNotNull(reference)
        if (all.isEmpty()) return@Canvas
        var lo = yMin ?: all.min()
        var hi = all.max()
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.12
        lo -= pad; hi += pad
        val bottomPad = if (xLabels.isEmpty()) 4f else 18.dp.toPx()
        val h = size.height - bottomPad
        val w = size.width
        fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
        for (i in 0..2) {
            val gy = h * i / 2f
            drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
        }
        if (reference != null) drawLine(labelColor.copy(alpha = 0.6f), Offset(0f, y(reference)), Offset(w, y(reference)), strokeWidth = 1.5f)
        for (s in series) {
            val n = s.values.size
            if (n == 0) continue
            fun x(i: Int) = if (n == 1) w / 2 else 6f + (w - 12f) * i / (n - 1)
            val path = Path()
            s.values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            if (s.fill && n > 1) {
                val area = Path().apply { addPath(path); lineTo(x(n - 1), h); lineTo(x(0), h); close() }
                drawPath(area, s.color.copy(alpha = 0.12f))
            }
            drawPath(path, s.color, style = Stroke(width = s.width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(s.color, radius = 4.5.dp.toPx(), center = Offset(x(n - 1), y(s.values.last())))
        }
        if (xLabels.isNotEmpty()) {
            xLabels.forEachIndexed { i, label ->
                val layout = measurer.measure(label, labelStyle)
                val fx = if (xLabels.size == 1) 0f else (w - layout.size.width) * i / (xLabels.size - 1)
                drawText(layout, topLeft = Offset(fx, size.height - layout.size.height))
            }
        }
    }
}

/** Vertical bars; the last one can be highlighted. */
@Composable
fun BarChart(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    highlight: Color? = null,
    xLabels: List<String> = emptyList(),
    description: String,
    /** Bars start from this value, not from zero: lap times of 64–80 s differ visibly. */
    floor: Double = 0.0,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = F.type.text, fontSize = 10.sp, color = F.c.text3)
    val radius = if (F.shape.pill) 4.dp else 0.dp
    val base = F.c.line
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val bottomPad = if (xLabels.isEmpty()) 2f else 18.dp.toPx()
        val h = size.height - bottomPad
        val maxV = max(values.max() - floor, 1e-6)
        val n = values.size
        val gap = 6.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val bh = ((v - floor) / maxV * (h - 4f)).toFloat().coerceIn(if (v > 0) 3f else 0f, h - 4f)
            val c = if (i == n - 1 && highlight != null) highlight else color
            drawRoundRect(c, Offset(i * (bw + gap), h - bh), Size(bw, bh), CornerRadius(radius.toPx()))
        }
        drawLine(base, Offset(0f, h), Offset(size.width, h), strokeWidth = 1f)
        xLabels.forEachIndexed { i, label ->
            if (label.isEmpty()) return@forEachIndexed
            val layout = measurer.measure(label, labelStyle)
            val cx = i * (bw + gap) + bw / 2 - layout.size.width / 2
            drawText(layout, topLeft = Offset(cx.coerceIn(0f, size.width - layout.size.width), size.height - layout.size.height))
        }
    }
}

@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier.size(64.dp, 24.dp)) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = max(values.max(), lo + 1e-6)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = 2f + (size.width - 4f) * i / (values.size - 1)
            val y = (size.height - 2f) - ((v - lo) / (hi - lo) * (size.height - 4f)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Circular progress, 0..1. */
@Composable
fun Ring(progress: Float, color: Color, modifier: Modifier = Modifier, track: Color = F.c.line, stroke: Dp = 6.dp, content: @Composable () -> Unit = {}) {
    Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { ring(progress, color, track, stroke.toPx()) }
        content()
    }
}

private fun DrawScope.ring(progress: Float, color: Color, track: Color, stroke: Float) {
    val d = min(size.width, size.height) - stroke
    val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
    drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(stroke))
    drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
}

/** Horizontal bar with a target band, e.g. weekly sets per muscle against 10–20. */
@Composable
fun RangeBar(value: Double, max: Double, bandFrom: Double, bandTo: Double, color: Color, modifier: Modifier = Modifier) {
    val band = F.c.surface2
    val track = F.c.line
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track.copy(alpha = 0.5f), cornerRadius = r)
        val x0 = (bandFrom / max * size.width).toFloat()
        val x1 = (bandTo / max * size.width).toFloat()
        drawRect(band, Offset(x0, 0f), Size(x1 - x0, size.height))
        val vw = (value.coerceAtMost(max) / max * size.width).toFloat()
        if (vw > 0) drawRoundRect(color, Offset.Zero, Size(vw, size.height), r)
    }
}

/**
 * One measure over time, read in two seconds (23 November: «графики перегруженные и непонятные»): one line in the text
 * colour, the latest point an accent dot with its value beside it, at most one reference line named at its end, no grid,
 * no fill, no y-axis. Points sit by date between [from] and [to] (epoch days), so a gap of weeks looks like one. [dots]:
 * the raw measurements under a smoothed line (body weight), small and muted. [invert]: lower is better (lap seconds) —
 * drawn higher, so up is better on every chart. [minSpan]: the least range of values the height stands for, so a few
 * tenths of noise do not fill it.
 */
@Composable
fun TrendChart(
    points: List<forma.core.analytics.Point>,
    from: Long,
    to: Long,
    description: String,
    modifier: Modifier = Modifier,
    dots: List<forma.core.analytics.Point> = emptyList(),
    endLabel: String? = null,
    reference: Double? = null,
    referenceLabel: String? = null,
    invert: Boolean = false,
    minSpan: Double = 0.0,
    startLabel: String? = null,
    endXLabel: String = "сейчас",
    height: Dp = 116.dp,
) {
    val measurer = rememberTextMeasurer()
    val muted = F.c.text3
    val ink = F.c.text
    val dotColor = F.c.strength
    val ring = F.c.bg
    val small = TextStyle(fontFamily = F.type.text, fontSize = 11.sp, color = muted)
    val label = TextStyle(fontFamily = F.type.text, fontSize = 12.sp, color = ink)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (points.isEmpty() && dots.isEmpty()) return@Canvas
        val values = points.map { it.value } + dots.map { it.value } + listOfNotNull(reference)
        var lo = values.min()
        var hi = values.max()
        if (hi - lo < minSpan) { val mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = mid + minSpan / 2 }
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.12
        lo -= pad; hi += pad
        val xBand = 16.dp.toPx()
        val top = 14.dp.toPx()
        val h = size.height - xBand - top
        val left = 4.dp.toPx()
        // Room on the right for the labels that sit there, measured: a clipped «цель 45,1 с» says nothing.
        val endW = endLabel?.let { measurer.measure(it, label).size.width + 9.dp.toPx() } ?: 0f
        val refW = referenceLabel?.let { measurer.measure(it, small).size.width + 6.dp.toPx() } ?: 0f
        val right = maxOf(endW, refW, 12.dp.toPx()) + 4.dp.toPx()
        val w = size.width - left - right
        fun x(day: Long) = left + w * trendX(day, from, to)
        fun y(v: Double): Float {
            val f = ((v - lo) / (hi - lo)).toFloat()
            return top + h * (if (invert) f else 1 - f)
        }
        val floor = size.height - xBand
        // The latest value's label first: the reference's name moves off it when the two would print over each other
        // (a lap a second from the goal, strength a point from the start).
        val end = points.lastOrNull()?.let { p -> Offset(x(p.epochDay), y(p.value)) }
        val endText = endLabel?.takeIf { end != null }?.let { measurer.measure(it, label) }
        val endTop = if (end != null && endText != null) (end.y - endText.size.height / 2f).coerceIn(0f, floor - endText.size.height) else null
        if (reference != null) {
            val ry = y(reference)
            drawLine(muted.copy(alpha = 0.7f), Offset(left, ry), Offset(left + w, ry), strokeWidth = 1.dp.toPx())
            referenceLabel?.let {
                val l = measurer.measure(it, small)
                val lh = l.size.height.toFloat()
                var ly = (ry - lh / 2f).coerceIn(0f, floor - lh)
                if (endTop != null && end != null && endText != null && ly < endTop + endText.size.height && ly + lh > endTop) {
                    val gap = 2.dp.toPx()
                    val above = endTop - lh - gap
                    val below = endTop + endText.size.height + gap
                    // Away from the point, on the side the line is; where that side has no room, the other.
                    ly = if (ry <= end.y) { if (above >= 0f) above else below } else { if (below + lh <= floor) below else above }
                    ly = ly.coerceIn(0f, floor - lh)
                }
                drawText(l, topLeft = Offset(left + w + 6.dp.toPx(), ly))
            }
        }
        dots.forEach { drawCircle(muted, radius = 2.5.dp.toPx(), center = Offset(x(it.epochDay), y(it.value))) }
        if (points.size >= 2) {
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.epochDay), y(p.value)) else path.lineTo(x(p.epochDay), y(p.value)) }
            drawPath(path, ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (end != null) {
            drawCircle(ring, radius = 6.dp.toPx(), center = end)
            drawCircle(dotColor, radius = 4.dp.toPx(), center = end)
            if (endText != null && endTop != null) {
                drawText(endText, topLeft = Offset((end.x + 9.dp.toPx()).coerceAtMost(size.width - endText.size.width.toFloat()), endTop))
            }
        }
        startLabel?.let { drawText(measurer.measure(it, small), topLeft = Offset(left, size.height - xBand + 2.dp.toPx())) }
        val e = measurer.measure(endXLabel, small)
        drawText(e, topLeft = Offset(left + w - e.size.width, size.height - xBand + 2.dp.toPx()))
    }
}

/** Where [day] sits across a [TrendChart] from [from] to [to], 0 to 1: by its date, so a gap of weeks looks like one. */
internal fun trendX(day: Long, from: Long, to: Long): Float {
    val span = (to - from).coerceAtLeast(1)
    return (day - from).coerceIn(0, span) / span.toFloat()
}

/**
 * Weeks side by side on a baseline, one bar each (at most 20 dp wide, rounded at the top only), past weeks muted;
 * [current]: the last one is under way — in the text colour so far, nothing while it is empty, «эта неделя» under it;
 * [usual]: a hairline named «обычная» at the person's usual week; [spikes]: weeks far over that line, the only colour on
 * the chart, named «скачок» (a review on 23 November: a spike measured against another yardstick sat on the line).
 */
@Composable
fun WeekBars(
    values: List<Double>,
    description: String,
    modifier: Modifier = Modifier,
    current: Boolean = true,
    usual: Double? = null,
    spikes: Set<Int> = emptySet(),
    startLabel: String? = null,
    height: Dp = 112.dp,
) {
    val measurer = rememberTextMeasurer()
    val muted = F.c.text3
    val ink = F.c.text
    val warn = F.c.warn
    val line = F.c.line
    val small = TextStyle(fontFamily = F.type.text, fontSize = 11.sp, color = muted)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val xBand = 16.dp.toPx()
        // Room above the tallest bar for its word («скачок»).
        val top = 20.dp.toPx()
        // Room on the right only for the usual line's name.
        val right = if (usual != null && usual > 0) measurer.measure("обычная", small).size.width + 10.dp.toPx() else 0f
        val h = size.height - xBand - top
        val w = size.width - right
        val hi = max(values.max(), usual ?: 0.0).takeIf { it > 0 } ?: 1.0
        val n = values.size
        val slot = w / n
        val bw = min(20.dp.toPx(), slot * 0.6f)
        val r = CornerRadius(4.dp.toPx())
        val base = top + h
        drawLine(line, Offset(0f, base), Offset(w, base), strokeWidth = 1f)
        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val bh = (v / hi * h).toFloat().coerceAtLeast(2.dp.toPx())
            val x = i * slot + (slot - bw) / 2
            val spike = i in spikes && !(current && i == n - 1)
            val color = when { current && i == n - 1 -> ink; spike -> warn; else -> muted }
            val bar = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(x, base - bh, x + bw, base, topLeftCornerRadius = r, topRightCornerRadius = r))
            }
            drawPath(bar, color)
            if (spike) {
                val l = measurer.measure("скачок", small.copy(color = warn))
                drawText(l, topLeft = Offset((x + bw / 2 - l.size.width / 2).coerceIn(0f, size.width - l.size.width), (base - bh - l.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
            }
        }
        if (usual != null && usual > 0) {
            val uy = (top + h - usual / hi * h).toFloat()
            drawLine(ink.copy(alpha = 0.55f), Offset(0f, uy), Offset(w, uy), strokeWidth = 1.dp.toPx())
            val l = measurer.measure("обычная", small)
            drawText(l, topLeft = Offset(w + 6.dp.toPx(), uy - l.size.height / 2f))
        }
        startLabel?.let { drawText(measurer.measure(it, small), topLeft = Offset(0f, size.height - xBand + 2.dp.toPx())) }
        if (current) {
            val e = measurer.measure("эта неделя", small)
            drawText(e, topLeft = Offset((w - slot / 2 - e.size.width / 2).coerceAtMost(size.width - e.size.width), size.height - xBand + 2.dp.toPx()))
        }
    }
}
