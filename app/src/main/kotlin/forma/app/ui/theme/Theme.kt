package forma.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Composable
fun FormaTheme(look: Look, content: @Composable () -> Unit) {
    val p = look.palette
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.text,
            surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.text2,
            outline = p.line, secondary = p.endurance, error = p.bad,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.text,
            surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.text2,
            outline = p.line, secondary = p.endurance, error = p.bad,
        )
    }
    CompositionLocalProvider(LocalLook provides look) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Current palette, shapes and type. */
object F {
    val look: Look @Composable @ReadOnlyComposable get() = LocalLook.current
    val c: Palette @Composable @ReadOnlyComposable get() = LocalLook.current.palette
    val shape: Shape @Composable @ReadOnlyComposable get() = LocalLook.current.shape
    val type: Typo @Composable @ReadOnlyComposable get() = LocalLook.current.typo
}

/** Text styles built from the current look. */
object T {
    @Composable @ReadOnlyComposable
    fun screenTitle(): TextStyle = TextStyle(
        fontFamily = F.type.display, fontWeight = F.type.displayWeight, fontSize = if (F.type.capsTitles) 52.sp else 32.sp,
        lineHeight = if (F.type.capsTitles) 48.sp else 38.sp, letterSpacing = (-0.02).em, color = F.c.text,
    )

    @Composable @ReadOnlyComposable
    fun title(size: TextUnit = 24.sp): TextStyle = TextStyle(
        fontFamily = F.type.display, fontWeight = F.type.displayWeight,
        fontSize = if (F.type.capsTitles) size * 1.35 else size, lineHeight = if (F.type.capsTitles) size * 1.3 else size * 1.15,
        letterSpacing = (-0.01).em, color = F.c.text,
    )

    @Composable @ReadOnlyComposable
    fun body(size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Medium): TextStyle = TextStyle(
        fontFamily = F.type.text, fontWeight = weight, fontSize = size, lineHeight = size * 1.35, color = F.c.text,
    )

    @Composable @ReadOnlyComposable
    fun secondary(size: TextUnit = 13.sp): TextStyle = body(size, FontWeight.Medium).copy(color = F.c.text2)

    /** Small label; in capital-label looks it is uppercase with tracking (apply [labelText] to the string). */
    @Composable @ReadOnlyComposable
    fun label(): TextStyle = TextStyle(
        fontFamily = F.type.text, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp,
        letterSpacing = if (F.type.capsLabels) 0.12.em else 0.02.em, color = F.c.text2,
    )

    /** Numbers: weights, reps, times. Tabular figures so digits don't jump. */
    @Composable @ReadOnlyComposable
    fun number(size: TextUnit, weight: FontWeight = F.type.numbersWeight): TextStyle = TextStyle(
        fontFamily = F.type.numbers, fontWeight = weight, fontSize = size, lineHeight = size * 1.05,
        fontFeatureSettings = "tnum", letterSpacing = (-0.01).em, color = F.c.text,
    )
}

@Composable @ReadOnlyComposable
fun labelText(s: String): String = if (F.type.capsLabels) s.uppercase() else s

/** Screen and card titles: uppercase in the poster look. */
@Composable @ReadOnlyComposable
fun titleText(s: String): String = if (F.type.capsTitles) s.uppercase() else s

/**
 * Digits in the numbers face; letters, units and "×" in the text face at a smaller size, so "80 кг × 7"
 * reads cleanly in condensed and monospaced looks.
 */
@Composable @ReadOnlyComposable
fun numText(s: String, base: TextStyle): AnnotatedString {
    // Relative sizes, so text that shrinks to fit (auto-size) keeps its proportions.
    val unit = SpanStyle(fontFamily = F.type.text, fontWeight = FontWeight.SemiBold, fontSize = 0.62.em)
    val sep = SpanStyle(fontFamily = F.type.text, fontWeight = FontWeight.Medium, fontSize = 0.8.em)
    // Words without numbers ("Без зала", "вес подбираем") are plain text at full size.
    if (s.none { it.isDigit() }) return buildAnnotatedString { withStyle(SpanStyle(fontFamily = F.type.text)) { append(s) } }
    return buildAnnotatedString {
        for ((i, ch) in s.withIndex()) {
            when {
                ch.isDigit() || ch in ",.:+−–-%" -> append(ch)
                ch == '×' || ch == '·' || ch == '≈' || ch == '/' -> withStyle(sep) { append(ch) }
                // A space between two words is as small as the words, not as wide as a digit.
                ch == ' ' && s.getOrNull(i - 1)?.isLetter() == true && s.getOrNull(i + 1)?.isLetter() == true -> withStyle(unit) { append(ch) }
                ch == ' ' -> append(ch)
                else -> withStyle(unit) { append(ch) }
            }
        }
    }
}
