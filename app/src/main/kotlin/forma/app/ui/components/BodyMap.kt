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
 * fully ([F.c.strength]); [selected] groups light fully too (the accents being chosen). [onTap]: a group tapped. The shapes
 * are docs/design/body/body.js, 100 × 210 a figure.
 */
@Composable
fun BodyMap(level: Map<Muscle, Float>, modifier: Modifier = Modifier, selected: Set<Muscle> = emptySet(), onTap: ((Muscle) -> Unit)? = null, description: String = "Карта мышц", labels: Boolean = true) {
    val figures = remember { listOf(FRONT, BACK).map { parts -> parts.map { it.muscle to PathParser().parsePathString(it.d).toPath() } } }
    val skin = if (F.c.dark) Color(0xFF262626) else Color(0xFFE6E6E3)
    val idle = if (F.c.dark) Color(0xFF3A3A3A) else Color(0xFFCFCFCB)
    val hot = F.c.strength
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(W * 2 / H).semantics { contentDescription = description }.let { m ->
                if (onTap == null) m else m.pointerInput(figures) {
                    detectTapGestures { at ->
                        val s = size.height / H
                        figures.forEachIndexed { i, parts ->
                            val x = (at.x - i * size.width / 2f) / s
                            val y = at.y / s
                            parts.firstOrNull { (mus, p) -> mus != null && contains(p, x, y) }?.first?.let(onTap)
                        }
                    }
                }
            },
        ) {
            val s = size.height / H
            figures.forEachIndexed { i, parts ->
                translate(left = i * size.width / 2f + (size.width / 2f - W * s) / 2f) {
                    scale(s, s, pivot = Offset.Zero) {
                        parts.forEach { (mus, p) ->
                            val c = when {
                                mus == null -> skin
                                mus in selected -> hot
                                else -> lerp(idle, hot, (level[mus] ?: 0f).coerceIn(0f, 1f))
                            }
                            drawPath(p, c)
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
    // Regions are whole units: a tap within a unit of the shape counts.
    return region.contains(x.toInt(), y.toInt()) || r.width() < 8 || r.height() < 8
}

private const val W = 100f
private const val H = 210f

private class Part(val d: String, val muscle: Muscle?)

private val FRONT = listOf(
        Part("M50,5 a10.5,11.5 0 1,0 0.01,0 Z", null),
        Part("M44.5,26 L55.5,26 L57,33 L43,33 Z", null),
        Part("M36,34 Q27,33 24,41 Q23,48 27,51 Q31,44 38,41 Z", Muscle.SHOULDERS),
        Part("M64,34 Q73,33 76,41 Q77,48 73,51 Q69,44 62,41 Z", Muscle.SHOULDERS),
        Part("M49,36 L49,56 Q42,59 35,54 Q31,48 35,42 Q41,36 49,36 Z", Muscle.CHEST),
        Part("M51,36 L51,56 Q58,59 65,54 Q69,48 65,42 Q59,36 51,36 Z", Muscle.CHEST),
        Part("M26,53 Q22,62 23,71 Q27,73 30,70 Q32,61 31,53 Q29,51 26,53 Z", Muscle.BICEPS),
        Part("M74,53 Q78,62 77,71 Q73,73 70,70 Q68,61 69,53 Q71,51 74,53 Z", Muscle.BICEPS),
        Part("M22.5,74 Q19,84 19.5,94 L24,95 Q27,85 29.5,74 Q26,72 22.5,74 Z", null),
        Part("M77.5,74 Q81,84 80.5,94 L76,95 Q73,85 70.5,74 Q74,72 77.5,74 Z", null),
        Part("M19,96 a3.6,4.4 0 1,0 0.01,0 Z", null),
        Part("M81,96 a3.6,4.4 0 1,0 0.01,0 Z", null),
        Part("M41,59 Q50,61 59,59 L58.5,90 Q50,95 41.5,90 Z", Muscle.CORE),
        Part("M35,57 L39.5,60 L40,90 Q36,86 35,78 Z", Muscle.CORE),
        Part("M65,57 L60.5,60 L60,90 Q64,86 65,78 Z", Muscle.CORE),
        Part("M35.5,92 Q50,99 64.5,92 L63,104 Q50,108 37,104 Z", null),
        Part("M37,106 Q32,124 35,144 Q40,149 46,145 Q49.5,126 49,108 Q43,108 37,106 Z", Muscle.QUADS),
        Part("M63,106 Q68,124 65,144 Q60,149 54,145 Q50.5,126 51,108 Q57,108 63,106 Z", Muscle.QUADS),
        Part("M36.5,147 Q41,151 45.5,147 L45,155 Q41,157 37,155 Z", null),
        Part("M63.5,147 Q59,151 54.5,147 L55,155 Q59,157 63,155 Z", null),
        Part("M36.5,157 Q34,172 37.5,188 L43.5,188 Q46.5,172 45,157 Q41,159 36.5,157 Z", Muscle.CALVES),
        Part("M63.5,157 Q66,172 62.5,188 L56.5,188 Q53.5,172 55,157 Q59,159 63.5,157 Z", Muscle.CALVES),
        Part("M36.5,190 L44,190 L45,197 Q40,200 34,198 Z", null),
        Part("M63.5,190 L56,190 L55,197 Q60,200 66,198 Z", null),
)

private val BACK = listOf(
        Part("M50,5 a10.5,11.5 0 1,0 0.01,0 Z", null),
        Part("M44.5,26 L55.5,26 L57,31 L43,31 Z", null),
        Part("M50,29 Q41,30 35,35 L38,46 Q44,43 50,44 Q56,43 62,46 L65,35 Q59,30 50,29 Z", Muscle.BACK),
        Part("M34,35 Q26,34 24,42 Q23,48 27,51 Q31,45 37,44 Z", Muscle.SHOULDERS),
        Part("M66,35 Q74,34 76,42 Q77,48 73,51 Q69,45 63,44 Z", Muscle.SHOULDERS),
        Part("M38,48 Q44,45 49,46 L49,76 Q42,74 39,68 Q36,58 38,48 Z", Muscle.BACK),
        Part("M62,48 Q56,45 51,46 L51,76 Q58,74 61,68 Q64,58 62,48 Z", Muscle.BACK),
        Part("M40.5,77 L59.5,77 L59,91 Q50,94 41,91 Z", Muscle.BACK),
        Part("M26,53 Q22,62 23,71 Q27,73 30,70 Q32,61 31,53 Q29,51 26,53 Z", Muscle.TRICEPS),
        Part("M74,53 Q78,62 77,71 Q73,73 70,70 Q68,61 69,53 Q71,51 74,53 Z", Muscle.TRICEPS),
        Part("M22.5,74 Q19,84 19.5,94 L24,95 Q27,85 29.5,74 Q26,72 22.5,74 Z", null),
        Part("M77.5,74 Q81,84 80.5,94 L76,95 Q73,85 70.5,74 Q74,72 77.5,74 Z", null),
        Part("M19,96 a3.6,4.4 0 1,0 0.01,0 Z", null),
        Part("M81,96 a3.6,4.4 0 1,0 0.01,0 Z", null),
        Part("M49,94 L49,110 Q42,114 36.5,108 Q34,99 38.5,94 Q44,92 49,94 Z", Muscle.GLUTES),
        Part("M51,94 L51,110 Q58,114 63.5,108 Q66,99 61.5,94 Q56,92 51,94 Z", Muscle.GLUTES),
        Part("M36.5,112 Q33,128 36,146 Q41,150 46,146 Q49,129 48.5,114 Q42,116 36.5,112 Z", Muscle.HAMSTRINGS),
        Part("M63.5,112 Q67,128 64,146 Q59,150 54,146 Q51,129 51.5,114 Q58,116 63.5,112 Z", Muscle.HAMSTRINGS),
        Part("M36.5,148 Q41,152 45.5,148 L45,155 Q41,157 37,155 Z", null),
        Part("M63.5,148 Q59,152 54.5,148 L55,155 Q59,157 63,155 Z", null),
        Part("M36,157 Q32,168 35.5,182 Q40,186 44,182 Q47,168 45,157 Q41,159 36,157 Z", Muscle.CALVES),
        Part("M64,157 Q68,168 64.5,182 Q60,186 56,182 Q53,168 55,157 Q59,159 64,157 Z", Muscle.CALVES),
        Part("M37,184 L44,184 L44.5,196 Q40,199 35,197 Z", null),
        Part("M63,184 L56,184 L55.5,196 Q60,199 65,197 Z", null),
)
