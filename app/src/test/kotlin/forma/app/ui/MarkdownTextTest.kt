package forma.app.ui

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import forma.app.ui.components.markdownText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Denis: the coach's «**…**» showed as stars. Its markdown is now styled text. */
class MarkdownTextTest {
    @Test fun boldLosesItsStars() {
        val t = markdownText("**Сегодня** отдых, а **завтра** бег.")
        assertEquals("Сегодня отдых, а завтра бег.", t.text)
        val bold = t.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { t.text.substring(it.start, it.end) }
        assertEquals(listOf("Сегодня", "завтра"), bold)
    }

    @Test fun listsHeadingsItalicAndCode() {
        val t = markdownText("## План\n* присед\n- жим *легче* обычного\n---\n`set_profile` и 3 * 4, set_week")
        assertEquals("План\n• присед\n• жим легче обычного\nset_profile и 3 * 4, set_week", t.text)
        assertTrue(t.spanStyles.any { it.item.fontStyle == FontStyle.Italic && t.text.substring(it.start, it.end) == "легче" })
        assertTrue(t.spanStyles.any { it.item.fontWeight == FontWeight.Bold && t.text.substring(it.start, it.end) == "План" })
    }

    @Test fun strayStarsGo() {
        assertEquals("Свежесть −37 %", markdownText("Свежесть **−37 %").text)
    }
}
