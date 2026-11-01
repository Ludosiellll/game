package forma.app.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The coach's markdown as styled text. Denis: the coach writes «**…**» and the app showed the stars. Bold (`**`, `__`),
 * italic (`*…*` hugging its words, so «3 * 4» stays), `code`, headings (`#`) as bold lines, `*`/`-`/`+` lists as «•»,
 * rules (`---`) dropped. `_` alone is left as is: function names like set_profile have it.
 */
fun markdownText(src: String): AnnotatedString = buildAnnotatedString {
    val lines = src.replace("\r\n", "\n").split('\n').filterNot { RULE.matches(it) }
    lines.forEachIndexed { i, line ->
        if (i > 0) append('\n')
        val heading = HEADING.find(line)
        val bullet = BULLET.find(line)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(line.substring(heading.range.last + 1)) }
            bullet != null -> {
                append(bullet.groupValues[1] + "• ")
                inline(line.substring(bullet.range.last + 1))
            }
            else -> inline(line)
        }
    }
}

private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
private val BULLET = Regex("""^(\s*)[*+-]\s+""")
private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])|`([^`]+)`""")

private fun AnnotatedString.Builder.inline(s: String) {
    var at = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(at, m.range.first).replace("**", ""))
        val (bold, bold2, italic, code) = m.destructured
        when {
            bold.isNotEmpty() || bold2.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(bold.ifEmpty { bold2 }) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(italic) }
            else -> append(code)
        }
        at = m.range.last + 1
    }
    append(s.substring(at).replace("**", ""))
}
