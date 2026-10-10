package forma.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val onAccent: Color,
    /** Data colours: strength, endurance, load/fatigue. */
    val strength: Color,
    val endurance: Color,
    val load: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val dark: Boolean,
)

@Immutable
data class Shape(
    val radius: Dp,
    val radiusSmall: Dp,
    /** Card outline; 0 — no outline. */
    val border: Dp,
    val pill: Boolean,
)

@Immutable
data class Typo(
    /** Screen titles and big headings. */
    val display: FontFamily,
    val displayWeight: FontWeight,
    /** Running text and controls. */
    val text: FontFamily,
    /** Big numbers: timers, weights. */
    val numbers: FontFamily,
    val numbersWeight: FontWeight,
    /** Small labels in capitals with tracking. */
    val capsLabels: Boolean,
    /** Multiplier for the huge timer digits (condensed faces can be taller). */
    val bigNumberScale: Float,
)

/** One design direction: palette, shapes and type. */
@Immutable
data class Look(val id: String, val name: String, val note: String, val palette: Palette, val shape: Shape, val typo: Typo)

object Looks {
    /** Near-black with a volt accent: high contrast in a dim gym, huge condensed digits. */
    val night = Look(
        "night", "Ночь", "Тёмный, контрастный, акцент «вольт»",
        Palette(
            bg = Color(0xFF0C0D0F), surface = Color(0xFF16171A), surface2 = Color(0xFF202125), line = Color(0xFF2C2D32),
            text = Color(0xFFF5F5F2), text2 = Color(0xFFA9ABB1), text3 = Color(0xFF6E7076),
            accent = Color(0xFFD7FF3A), onAccent = Color(0xFF0C0D0F),
            strength = Color(0xFFD7FF3A), endurance = Color(0xFF4FD1FF), load = Color(0xFFFF8A3D),
            good = Color(0xFF5BE38C), warn = Color(0xFFFFC94D), bad = Color(0xFFFF5C5C), dark = true,
        ),
        Shape(radius = 20.dp, radiusSmall = 12.dp, border = 0.dp, pill = true),
        Typo(Fonts.manrope, FontWeight.ExtraBold, Fonts.manrope, Fonts.condensed, FontWeight.Black, capsLabels = true, bigNumberScale = 1.15f),
    )

    /** Warm paper, black type, one cobalt accent: calm and editorial. */
    val paper = Look(
        "paper", "Бумага", "Светлый, спокойный, много воздуха",
        Palette(
            bg = Color(0xFFF3F1EC), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEAE7E0), line = Color(0xFFDDD9D0),
            text = Color(0xFF121212), text2 = Color(0xFF5D5B57), text3 = Color(0xFF8E8B85),
            accent = Color(0xFF2346FF), onAccent = Color(0xFFFFFFFF),
            strength = Color(0xFF121212), endurance = Color(0xFF2346FF), load = Color(0xFFE2582B),
            good = Color(0xFF1E8E5A), warn = Color(0xFFC98A0B), bad = Color(0xFFD23C3C), dark = false,
        ),
        Shape(radius = 18.dp, radiusSmall = 10.dp, border = 0.dp, pill = true),
        Typo(Fonts.onest, FontWeight.Bold, Fonts.onest, Fonts.onest, FontWeight.SemiBold, capsLabels = false, bigNumberScale = 0.9f),
    )

    /** Sport poster: cream and black with a signal orange, wide display type, hard outlines. */
    val poster = Look(
        "poster", "Постер", "Смелый, крупная типографика, оранжевый",
        Palette(
            bg = Color(0xFFF1ECE2), surface = Color(0xFFFFFBF3), surface2 = Color(0xFFE6DFD1), line = Color(0xFF151413),
            text = Color(0xFF151413), text2 = Color(0xFF4F4A43), text3 = Color(0xFF857E73),
            accent = Color(0xFFFF4F12), onAccent = Color(0xFF151413),
            strength = Color(0xFFFF4F12), endurance = Color(0xFF2F55FF), load = Color(0xFF151413),
            good = Color(0xFF1B8A4B), warn = Color(0xFFD99A00), bad = Color(0xFFD7261E), dark = false,
        ),
        Shape(radius = 6.dp, radiusSmall = 4.dp, border = 2.dp, pill = false),
        Typo(Fonts.unbounded, FontWeight.Black, Fonts.manrope, Fonts.unbounded, FontWeight.Bold, capsLabels = true, bigNumberScale = 0.82f),
    )

    /** Soft colourful containers and big rounded shapes, in the spirit of Material 3 Expressive. */
    val bloom = Look(
        "bloom", "Цвет", "Мягкие цветные плашки, крупные скругления",
        Palette(
            bg = Color(0xFFFAF7FF), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEDE6FB), line = Color(0xFFE2DAF2),
            text = Color(0xFF1C1631), text2 = Color(0xFF5E5675), text3 = Color(0xFF8F88A3),
            accent = Color(0xFF5B3FD6), onAccent = Color(0xFFFFFFFF),
            strength = Color(0xFF5B3FD6), endurance = Color(0xFF00A887), load = Color(0xFFFF7A59),
            good = Color(0xFF00A887), warn = Color(0xFFE59B00), bad = Color(0xFFE5484D), dark = false,
        ),
        Shape(radius = 28.dp, radiusSmall = 16.dp, border = 0.dp, pill = true),
        Typo(Fonts.onest, FontWeight.ExtraBold, Fonts.onest, Fonts.onest, FontWeight.Bold, capsLabels = false, bigNumberScale = 0.92f),
    )

    val all = listOf(night, paper, poster, bloom)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: night
}

val LocalLook = staticCompositionLocalOf { Looks.night }
