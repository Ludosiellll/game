package forma.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.app.ui.theme.labelText
import forma.core.model.Muscle

/** How a muscle group did against its week: below it, within it, over it. */
enum class Tone { LOW, OK, HIGH }

/**
 * A person seen from the front and from the back, each muscle group lit by how much it is worked: [level] 0 — idle, a
 * little — a quieter step of [F.c.strength], from a quarter of the full — fully; [selected] groups light fully too (the
 * accents being chosen). [tones]: a week against the plan — what got its sets lit, the rest at rest (a review on 23
 * November: rings and three steps read as «everything trained»). [onTap]: a group tapped. The figures are an anatomical
 * muscle map ([FRONT_SHAPES], [BACK_SHAPES]; MIT, see BodyShapes.kt): Denis, 23 November — «некрасивая модель человека …
 * чтобы была такая же красивая как будто с книги по человеческому телу».
 */

@Composable
fun BodyMap(
    level: Map<Muscle, Float>,
    modifier: Modifier = Modifier,
    selected: Set<Muscle> = emptySet(),
    onTap: ((Muscle) -> Unit)? = null,
    description: String = "Карта мышц",
    labels: Boolean = true,
    tones: Map<Muscle, Tone> = emptyMap(),
) {
    val figures = remember {
        listOf(FRONT_OUTLINE to FRONT_SHAPES, BACK_OUTLINE to BACK_SHAPES).map { (outline, shapes) ->
            PathParser().parsePathString(outline).toPath() to shapes.map { it to PathParser().parsePathString(it.d).toPath() }
        }
    }
    // The body, its outline, skin (head, hands, feet), a muscle at rest; a worked one goes to the strength colour.
    val body = if (F.c.dark) Color(0xFF1C1C1C) else Color(0xFFEFEFEC)
    val line = if (F.c.dark) Color(0xFF4A4A4A) else Color(0xFFB9B9B4)
    val skin = if (F.c.dark) Color(0xFF2B2B2B) else Color(0xFFDCDCD8)
    val idle = restColor()
    val hot = F.c.strength
    val little = quieter(hot, F.c.dark)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(W * 2 / H).semantics { contentDescription = description }.let { m ->
                if (onTap == null) m else m.pointerInput(figures) {
                    detectTapGestures { at ->
                        val s = size.height / H
                        figures.forEachIndexed { i, (_, parts) ->
                            val x = (at.x - i * size.width / 2f - (size.width / 2f - W * s) / 2f) / s + X0
                            val y = at.y / s + Y0
                            parts.lastOrNull { (shape, p) -> shape.muscle != null && contains(p, x, y) }?.first?.muscle?.let(onTap)
                        }
                    }
                }
            },
        ) {
            val s = size.height / H
            figures.forEachIndexed { i, (outline, parts) ->
                translate(left = i * size.width / 2f + (size.width / 2f - W * s) / 2f) {
                    scale(s, s, pivot = Offset.Zero) {
                        translate(left = -X0, top = -Y0) {
                            drawPath(outline, body)
                            drawPath(outline, line, style = Stroke(width = 1.5f / s))
                            parts.forEach { (shape, p) ->
                                val c = when {
                                    shape.muscle == null -> if (shape.flesh) idle else skin
                                    shape.muscle in selected -> hot
                                    // Short of the plan: at rest, as a group not trained — a figure mostly short early in a
                                    // week must read as such, not lit; the words above it name which.
                                    tones[shape.muscle] == Tone.LOW -> idle
                                    tones[shape.muscle] != null -> hot
                                    // A plain level (a session's muscles in the program builder): worked a little, or worked.
                                    (level[shape.muscle] ?: 0f) >= 0.25f -> hot
                                    (level[shape.muscle] ?: 0f) > 0f -> little
                                    else -> idle
                                }
                                drawPath(p, c)
                            }
                        }
                    }
                }
            }
        }
        if (labels) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            Text(labelText("Спереди"), style = T.label())
            Text(labelText("Сзади"), style = T.label())
        }
    }
}

/** A muscle at rest: the grey of the map, and of its legend. */
@Composable
private fun restColor() = if (F.c.dark) Color(0xFF3B3B3B) else Color(0xFFC8C8C3)

/** What a week's map shows ([BodyMap] with tones): lit — the plan's sets done, at rest — short of them. */
@Composable
fun BodyMapLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("по плану" to F.c.strength, "мало" to restColor()).forEach { (name, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(c, CircleShape))
                Text(name, style = T.secondary(13.sp))
            }
        }
    }
}

private fun contains(p: Path, x: Float, y: Float): Boolean {
    val r = android.graphics.RectF()
    val ap = p.asAndroidPath()
    ap.computeBounds(r, true)
    if (!r.contains(x, y)) return false
    val region = android.graphics.Region()
    region.setPath(ap, android.graphics.Region(r.left.toInt() - 1, r.top.toInt() - 1, r.right.toInt() + 1, r.bottom.toInt() + 1))
    // Regions are whole units: a tap within a unit of the shape counts; a thin shape, anywhere in its bounds.
    return region.contains(x.toInt(), y.toInt()) || r.width() < 40 || r.height() < 40
}

/**
 * The colour of a muscle worked a little: [full]'s own hue, darker and less vivid on a dark body, lighter on a light one —
 * a blend with the grey came out a muddy brown. In OKLab, so the hue holds (checked against the unlit grey and the full
 * colour in every look: ΔE 10–40, 23 November).
 */
private fun quieter(full: Color, dark: Boolean): Color {
    fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    fun srgb(c: Double) = (if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - 0.055).coerceIn(0.0, 1.0).toFloat()
    val r = lin(full.red); val g = lin(full.green); val b = lin(full.blue)
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val k = if (dark) 0.6 else 0.5
    val L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s + if (dark) -0.16 else 0.14
    val A = (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s) * k
    val B = (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s) * k
    val l3 = Math.pow(L + 0.3963377774 * A + 0.2158037573 * B, 3.0)
    val m3 = Math.pow(L - 0.1055613458 * A - 0.0638541728 * B, 3.0)
    val s3 = Math.pow(L - 0.0894841775 * A - 1.2914855480 * B, 3.0)
    return Color(
        srgb(4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3),
        srgb(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3),
        srgb(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3),
    )
}

/** The frame of one figure in the shapes' units: the outlines span x 40–681, y 164–1353. */
private const val X0 = 30f
private const val Y0 = 150f
private const val W = 662f
private const val H = 1215f
