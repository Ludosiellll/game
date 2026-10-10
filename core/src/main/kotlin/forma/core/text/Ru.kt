package forma.core.text

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Russian number and unit formatting shared by the UI and the voice coach. */
object Ru {
    /** Russian plural: 1 минута, 2 минуты, 5 минут, 21 минута. */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val m100 = abs(n) % 100
        val m10 = abs(n) % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1L -> one
            m10 in 2..4 -> few
            else -> many
        }
    }

    fun plural(n: Int, one: String, few: String, many: String) = plural(n.toLong(), one, few, many)

    fun minutes(n: Int) = "$n ${plural(n, "минута", "минуты", "минут")}"
    fun seconds(n: Int) = "$n ${plural(n, "секунда", "секунды", "секунд")}"
    fun reps(n: Int) = "$n ${plural(n, "повтор", "повтора", "повторов")}"
    fun sets(n: Int) = "$n ${plural(n, "подход", "подхода", "подходов")}"
    fun times(n: Int) = "$n ${plural(n, "раз", "раза", "раз")}"
    fun weeks(n: Int) = "$n ${plural(n, "неделя", "недели", "недель")}"
    fun days(n: Int) = "$n ${plural(n, "день", "дня", "дней")}"
    fun km(n: Int) = "$n км"

    /** "2 минуты 30 секунд", "45 секунд", "10 минут" — for speech and captions. */
    fun durationWords(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return when {
            m == 0 -> seconds(s)
            s == 0 -> minutes(m)
            else -> "${minutes(m)} ${seconds(s)}"
        }
    }

    /** "1:05:09" or "25:04" clock. */
    fun clock(totalSeconds: Int): String {
        val t = totalSeconds.coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Pace "5:12" from seconds per km. */
    fun pace(secPerKm: Double): String {
        if (!secPerKm.isFinite() || secPerKm <= 0) return "–:––"
        val total = secPerKm.roundToInt()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    /** Weight with a decimal comma and no trailing zero: "62,5", "60". */
    fun kg(x: Double): String {
        val r = (x * 100).roundToInt() / 100.0
        val s = if (r == r.toLong().toDouble()) r.toLong().toString() else {
            String.format(Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.')
        }
        return s.replace('.', ',')
    }

    /** "1,0" style decimal with one digit. */
    fun one(x: Double): String = String.format(Locale.ROOT, "%.1f", x).replace('.', ',')

    /** Kilometres with two decimals and no unit: "3,90". */
    fun km2(meters: Double): String = String.format(Locale.ROOT, "%.2f", meters / 1000).replace('.', ',')

    /** Distance "5,2 км" or "850 м". */
    fun distance(meters: Double): String =
        if (meters < 1000) "${meters.roundToInt()} м" else "${String.format(Locale.ROOT, "%.2f", meters / 1000).replace('.', ',')} км"
}
