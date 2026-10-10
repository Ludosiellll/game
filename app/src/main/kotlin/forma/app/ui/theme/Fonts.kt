package forma.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import forma.app.R

/** Variable fonts, instanced at the weights the UI uses. All have full Cyrillic (SIL OFL). */
object Fonts {
    private fun family(res: Int, vararg weights: Int) = FontFamily(
        weights.map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) },
    )

    val manrope = family(R.font.manrope, 400, 500, 600, 700, 800)
    val onest = family(R.font.onest, 300, 400, 500, 600, 700, 800)
    val rubik = family(R.font.rubik, 400, 500, 600, 700)
    val mono = family(R.font.jetbrains_mono, 500, 600, 700, 800)
    val condensed = family(R.font.sofia_sans_extra_condensed, 500, 600, 700, 800, 900)
}
