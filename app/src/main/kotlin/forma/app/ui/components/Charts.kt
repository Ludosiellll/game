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
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = F.type.text, fontSize = 10.sp, color = F.c.text3)
    val radius = if (F.shape.pill) 4.dp else 0.dp
    val base = F.c.line
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val bottomPad = if (xLabels.isEmpty()) 2f else 18.dp.toPx()
        val h = size.height - bottomPad
        val maxV = max(values.max(), 1e-6)
        val n = values.size
        val gap = 6.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val bh = (v / maxV * (h - 4f)).toFloat().coerceAtLeast(if (v > 0) 3f else 0f)
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
