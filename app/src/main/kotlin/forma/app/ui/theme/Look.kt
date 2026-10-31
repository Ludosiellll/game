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
    /** Data colours: strength (gym), endurance (running), load/fatigue. */
    val strength: Color,
    val endurance: Color,
    val load: Color,
    /** Text/icons drawn on [strength] and [endurance] fills. */
    val onData: Color,
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
    /** Sections divided by hairlines instead of cards (minimal). */
    val flat: Boolean = false,
)

@Immutable
data class Typo(
    /** Screen titles and big headings. */
    val display: FontFamily,
    val displayWeight: FontWeight,
    /** Running text and controls. */
    val text: FontFamily,
    /** Numbers: timers, weights, reps. */
    val numbers: FontFamily,
    val numbersWeight: FontWeight,
    /** Small labels in capitals with tracking. */
    val capsLabels: Boolean,
    /** Uppercase screen titles and buttons (poster). */
    val capsTitles: Boolean,
    /** Multiplier for the huge timer digits. */
    val bigNumberScale: Float,
    /** Huge uppercase titles in a heavy wide face, plain small-caps buttons (minimal). */
    val mega: Boolean = false,
)

/** One design direction from the design board: palette, shapes and type. */
@Immutable
data class Look(val id: String, val name: String, val note: String, val palette: Palette, val shape: Shape, val typo: Typo)

object Looks {
    /** А · Карбон: near-black, lime for the gym, cyan for running, monospaced instrument digits. */
    val carbon = Look(
        "carbon", "Карбон", "Тёмный. Лайм — зал, бирюза — бег",
        Palette(
            bg = Color(0xFF0B0D10), surface = Color(0xFF15181D), surface2 = Color(0xFF1E232A), line = Color(0xFF2A3039),
            text = Color(0xFFF2F4F6), text2 = Color(0xFFA0A8B3), text3 = Color(0xFF6B7480),
            accent = Color(0xFFC6F135), onAccent = Color(0xFF0B0D10),
            strength = Color(0xFFC6F135), endurance = Color(0xFF3DD6F5), load = Color(0xFFFF8A3D), onData = Color(0xFF0B0D10),
            good = Color(0xFF5BE38C), warn = Color(0xFFFFC94D), bad = Color(0xFFFF5C5C), dark = true,
        ),
        Shape(radius = 20.dp, radiusSmall = 12.dp, border = 0.dp, pill = true),
        Typo(Fonts.manrope, FontWeight.ExtraBold, Fonts.manrope, Fonts.mono, FontWeight.ExtraBold, capsLabels = true, capsTitles = false, bigNumberScale = 1.0f),
    )

    /** Б · Свет: cool light, cobalt for the gym, vermilion for running, thin large numbers. */
    val light = Look(
        "light", "Свет", "Светлый и спокойный. Кобальт — зал, оранжевый — бег",
        Palette(
            bg = Color(0xFFF5F6F8), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEDEFF3), line = Color(0xFFE1E4EA),
            text = Color(0xFF14161A), text2 = Color(0xFF5B6170), text3 = Color(0xFF8A90A0),
            accent = Color(0xFF2F5BEA), onAccent = Color(0xFFFFFFFF),
            strength = Color(0xFF2F5BEA), endurance = Color(0xFFE2582B), load = Color(0xFF9043A1), onData = Color(0xFFFFFFFF),
            good = Color(0xFF1E8E5A), warn = Color(0xFFB7791F), bad = Color(0xFFD23C3C), dark = false,
        ),
        Shape(radius = 22.dp, radiusSmall = 14.dp, border = 0.dp, pill = false),
        Typo(Fonts.onest, FontWeight.SemiBold, Fonts.onest, Fonts.onest, FontWeight.Normal, capsLabels = false, capsTitles = false, bigNumberScale = 0.82f),
    )

    /** В · Афиша: race poster — paper and ink, one signal orange, giant condensed numbers, hard rules. */
    val poster = Look(
        "poster", "Афиша", "Спортивный плакат. Огромные узкие цифры, оранжевый",
        Palette(
            bg = Color(0xFFF2F2EE), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE6E6E0), line = Color(0xFF0A0A0A),
            text = Color(0xFF0A0A0A), text2 = Color(0xFF4A4A46), text3 = Color(0xFF7A7A74),
            accent = Color(0xFFFF5A1F), onAccent = Color(0xFF0A0A0A),
            strength = Color(0xFFFF5A1F), endurance = Color(0xFF0E4EC8), load = Color(0xFF0A0A0A), onData = Color(0xFF0A0A0A),
            good = Color(0xFF127A3B), warn = Color(0xFFB45F00), bad = Color(0xFFD03B3B), dark = false,
        ),
        Shape(radius = 4.dp, radiusSmall = 2.dp, border = 2.dp, pill = false),
        Typo(Fonts.condensed, FontWeight.Black, Fonts.manrope, Fonts.condensed, FontWeight.Black, capsLabels = true, capsTitles = true, bigNumberScale = 1.35f),
    )

    /** Г · Тон: soft Material 3 Expressive — tonal containers, large rounded shapes. */
    val tone = Look(
        "tone", "Тон", "Мягкий Material 3: цветные плашки, крупные скругления",
        Palette(
            bg = Color(0xFFF9F9FE), surface = Color(0xFFEEEDF5), surface2 = Color(0xFFE3DFFF), line = Color(0xFFC7C5D5),
            text = Color(0xFF1C1B20), text2 = Color(0xFF474553), text3 = Color(0xFF777585),
            accent = Color(0xFF3E53C7), onAccent = Color(0xFFFFFFFF),
            strength = Color(0xFF3E53C7), endurance = Color(0xFF036A65), load = Color(0xFF9A4A13), onData = Color(0xFFFFFFFF),
            good = Color(0xFF1E7A45), warn = Color(0xFF9A4A13), bad = Color(0xFFBA1A1A), dark = false,
        ),
        Shape(radius = 28.dp, radiusSmall = 16.dp, border = 0.dp, pill = true),
        Typo(Fonts.rubik, FontWeight.SemiBold, Fonts.rubik, Fonts.rubik, FontWeight.SemiBold, capsLabels = false, capsTitles = false, bigNumberScale = 0.85f),
    )

    /**
     * Д · Минимализм (Denis, 30.10): white, black ink, one orange, huge uppercase titles, hairlines instead of cards,
     * black pill buttons (docs/design/minimal).
     */
    val minimal = Look(
        "minimal", "Минимализм", "Белый, крупные заголовки, линии вместо карточек",
        Palette(
            bg = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF1F1EF), line = Color(0xFFE3E3E1),
            text = Color(0xFF111111), text2 = Color(0xFF6F6F6F), text3 = Color(0xFF9B9B9B),
            accent = Color(0xFF111111), onAccent = Color(0xFFFFFFFF),
            strength = Color(0xFFFF4D12), endurance = Color(0xFF111111), load = Color(0xFF9B9B9B), onData = Color(0xFFFFFFFF),
            good = Color(0xFF1F8A4C), warn = Color(0xFFC46A00), bad = Color(0xFFD7372B), dark = false,
        ),
        Shape(radius = 14.dp, radiusSmall = 10.dp, border = 0.dp, pill = true, flat = true),
        Typo(Fonts.interDisplay, FontWeight.Black, Fonts.inter, Fonts.inter, FontWeight.ExtraBold, capsLabels = true, capsTitles = false, bigNumberScale = 1.1f, mega = true),
    )

    /** Минимализм, тёмный: the same on black. */
    val minimalDark = Look(
        "minimal_dark", "Минимализм тёмный", "Чёрный, крупные заголовки, линии вместо карточек",
        Palette(
            bg = Color(0xFF0D0D0D), surface = Color(0xFF0D0D0D), surface2 = Color(0xFF1C1C1C), line = Color(0xFF2B2B2B),
            text = Color(0xFFF4F4F2), text2 = Color(0xFF9E9E9E), text3 = Color(0xFF6C6C6C),
            accent = Color(0xFFF4F4F2), onAccent = Color(0xFF0D0D0D),
            strength = Color(0xFFFF5A1F), endurance = Color(0xFFF4F4F2), load = Color(0xFF8C8C8C), onData = Color(0xFF0D0D0D),
            good = Color(0xFF4CD787), warn = Color(0xFFF2B84B), bad = Color(0xFFFF5C5C), dark = true,
        ),
        Shape(radius = 14.dp, radiusSmall = 10.dp, border = 0.dp, pill = true, flat = true),
        Typo(Fonts.interDisplay, FontWeight.Black, Fonts.inter, Fonts.inter, FontWeight.ExtraBold, capsLabels = true, capsTitles = false, bigNumberScale = 1.1f, mega = true),
    )

    val all = listOf(minimalDark, minimal, carbon, light, poster, tone)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: carbon

    /** The newest of the looks the app has moved everyone to: 1 — «Минимализм» (30.10), 2 — its dark one (Denis, 31.10). */
    const val DESIGN_VERSION = 2

    /**
     * The look an install starts this version with, once: the dark «Минимализм». The white one, given by the move before,
     * turns dark too; another look picked by hand after that stays.
     */
    fun moved(s: forma.app.data.Settings): forma.app.data.Settings = when {
        s.designVersion >= DESIGN_VERSION -> s
        s.designVersion < 1 || s.look == minimal.id -> s.copy(look = minimalDark.id, designVersion = DESIGN_VERSION)
        else -> s.copy(designVersion = DESIGN_VERSION)
    }
}

val LocalLook = staticCompositionLocalOf { Looks.carbon }
