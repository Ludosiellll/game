package forma.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke icons drawn on a 24-unit grid; tinted by the caller. */
object Ic {
    private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                pathData = addPathNodes(p), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun fill(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) b.addPath(pathData = addPathNodes(p), fill = SolidColor(Color.Black))
        return b.build()
    }

    val dumbbell = stroke("dumbbell", "M6 7v10M3 9.5v5M18 7v10M21 9.5v5M6 12h12", width = 2.2f)
    val run = stroke("run", "M16.3 4.5a1.8 1.8 0 1 0 -3.6 0a1.8 1.8 0 1 0 3.6 0", "M7 21l3.6-6.4 3.4 2.4v4M5.5 12.5L9.5 9l4.5 1 2.5 3.5h3M9.5 9l1.7 5.6")
    val home = stroke("home", "M4 11l8-7 8 7v9h-5v-6H9v6H4z")
    val calendar = stroke("calendar", "M6 5h12a2.5 2.5 0 0 1 2.5 2.5v10A2.5 2.5 0 0 1 18 20H6a2.5 2.5 0 0 1-2.5-2.5v-10A2.5 2.5 0 0 1 6 5z", "M3.5 10h17M8 3v4M16 3v4")
    val chart = stroke("chart", "M4 19V5M4 19h16M8 15l4-4 3 3 5-6")
    val more = fill("more", "M5 10a2 2 0 1 1 0 4a2 2 0 1 1 0-4z", "M12 10a2 2 0 1 1 0 4a2 2 0 1 1 0-4z", "M19 10a2 2 0 1 1 0 4a2 2 0 1 1 0-4z")
    val back = stroke("back", "M15 5l-7 7 7 7", width = 2.4f)
    val check = stroke("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.8f)
    val plus = stroke("plus", "M12 5v14M5 12h14", width = 2.4f)
    val minus = stroke("minus", "M5 12h14", width = 2.4f)
    val up = stroke("up", "M12 19V5M6 11l6-6 6 6", width = 2.4f)
    val down = stroke("down", "M12 5v14M6 13l6 6 6-6", width = 2.4f)
    val right = stroke("right", "M5 12h14M13 6l6 6-6 6")
    val chevron = stroke("chevron", "M9 5l7 7-7 7")
    val pause = fill("pause", "M7.2 5h1.6a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1-1.2 1.2H7.2A1.2 1.2 0 0 1 6 17.8V6.2A1.2 1.2 0 0 1 7.2 5z", "M15.2 5h1.6a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1-1.2 1.2h-1.6a1.2 1.2 0 0 1-1.2-1.2V6.2A1.2 1.2 0 0 1 15.2 5z")
    val play = fill("play", "M8 5.5v13l11-6.5z")
    val stop = fill("stop", "M7 6h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z")
    val lock = stroke("lock", "M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3")
    val timer = stroke("timer", "M19 13a7 7 0 1 1-14 0a7 7 0 1 1 14 0z", "M12 9v4l2.5 2M10 3h4")
    val flame = stroke("flame", "M12 21c-3.9 0-6.5-2.6-6.5-6.2 0-3.3 2.3-5.2 3.4-7.8.6 1.6 1.6 2.6 2.6 3 .2-2.8 1.4-5 3.6-7 .3 3.1 3.4 5.4 3.4 10.2 0 4.2-2.7 7.8-6.5 7.8z")
    val heart = stroke("heart", "M12 20s-7-4.3-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.7-7 10-7 10z")
    val scale = stroke("scale", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M8.5 9.5a5 5 0 0 1 7 0L13 12")
    val calc = stroke("calc", "M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8 7h8M8 12h1M12 12h1M16 12h0M8 16h1M12 16h1M16 16h0")
    val list = stroke("list", "M9 6h11M9 12h11M9 18h11M4.5 6h0M4.5 12h0M4.5 18h0", width = 2.4f)
    val gear = stroke("gear", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z", "M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7")
    val user = stroke("user", "M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0z", "M4.5 20c1.2-3.6 4-5.5 7.5-5.5s6.3 1.9 7.5 5.5")
    val trash = stroke("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3")
    val swap = stroke("swap", "M7 7h12l-3-3M17 17H5l3 3")
    val info = stroke("info", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z", "M12 11v5M12 8h0", width = 2.2f)
    val trophy = stroke("trophy", "M8 4h8v5a4 4 0 0 1-8 0V4zM8 6H5a3 3 0 0 0 3 3M16 6h3a3 3 0 0 1-3 3M12 13v4M8.5 20h7")
    val pin = stroke("pin", "M18 10c0 5-6 11-6 11S6 15 6 10a6 6 0 0 1 12 0z", "M14 10a2 2 0 1 1-4 0a2 2 0 1 1 4 0z")
    val sound = stroke("sound", "M5 9v6h4l5 4V5L9 9H5z", "M17 9.5a3.5 3.5 0 0 1 0 5")
}
