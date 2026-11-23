package forma.app.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.app.ui.theme.labelText
import forma.core.model.Muscle

/**
 * A person seen from the front and from the back, each muscle group lit by how much it is worked: [level] 0 — idle, 1 —
 * fully ([F.c.strength]); [selected] groups light fully too (the accents being chosen). [onTap]: a group tapped. The
 * figures are an anatomical muscle map ([FRONT_SHAPES], [BACK_SHAPES]; MIT, see BodyShapes.kt): Denis, 23 November —
 * «некрасивая модель человека … чтобы была такая же красивая как будто с книги по человеческому телу».
 */
@Composable
fun BodyMap(level: Map<Muscle, Float>, modifier: Modifier = Modifier, selected: Set<Muscle> = emptySet(), onTap: ((Muscle) -> Unit)? = null, description: String = "Карта мышц", labels: Boolean = true) {
    val figures = remember {
        listOf(FRONT_OUTLINE to FRONT_SHAPES, BACK_OUTLINE to BACK_SHAPES).map { (outline, shapes) ->
            PathParser().parsePathString(outline).toPath() to shapes.map { it to PathParser().parsePathString(it.d).toPath() }
        }
    }
    // The body, its outline, skin (head, hands, feet), a muscle at rest; a worked one goes to the strength colour.
    val body = if (F.c.dark) Color(0xFF1C1C1C) else Color(0xFFEFEFEC)
    val line = if (F.c.dark) Color(0xFF4A4A4A) else Color(0xFFB9B9B4)
    val skin = if (F.c.dark) Color(0xFF2B2B2B) else Color(0xFFDCDCD8)
    val idle = if (F.c.dark) Color(0xFF3B3B3B) else Color(0xFFC8C8C3)
    val hot = F.c.strength
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
                                    else -> lerp(idle, hot, (level[shape.muscle] ?: 0f).coerceIn(0f, 1f))
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

/** The frame of one figure in the shapes' units: the outlines span x 40–681, y 164–1353. */
private const val X0 = 30f
private const val Y0 = 150f
private const val W = 662f
private const val H = 1215f
