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

    /** "1 круг", "3 круга", "6,5 круга", "3,75 круга" (a fraction takes the genitive singular). */
    fun laps(x: Double): String =
        if (x % 1.0 == 0.0) "${x.toInt()} ${plural(x.toInt(), "круг", "круга", "кругов")}"
        else String.format(Locale.ROOT, "%.2f", x).trimEnd('0').trimEnd('.').replace('.', ',') + " круга"

    /** The same words kept on one line: "круг 230 м — 51 с" never leaves "с" alone on the next one. */
    fun nobreak(s: String): String = s.replace(' ', '\u00A0')

    /** A lap time: "52 с" under a minute, "1:44" from a minute up. */
    fun lap(sec: Double): String {
        val s = sec.roundToInt()
        return if (s < 60) "$s с" else clock(s)
    }

    /** Lap times "51–52 с" (or one number when both round the same). */
    fun lapRange(fast: Double, slow: Double): String {
        val a = fast.roundToInt()
        val b = slow.roundToInt()
        return when {
            a == b -> lap(fast)
            b < 60 -> "$a–$b с"
            else -> "${clock(a)}–${clock(b)}"
        }
    }

    /** Distance "5,2 км" or "850 м". */
    fun distance(meters: Double): String =
        if (meters < 1000) "${meters.roundToInt()} м" else "${String.format(Locale.ROOT, "%.2f", meters / 1000).replace('.', ',')} км"

    private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

    /** "13 октября". */
    fun date(d: java.time.LocalDate): String = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"

    /** "6–12 октября", "29 сентября – 5 октября". */
    fun dates(a: java.time.LocalDate, b: java.time.LocalDate): String =
        if (a == b) date(a) else if (a.month == b.month) "${a.dayOfMonth}–${date(b)}" else "${date(a)} – ${date(b)}"

    /** "+12 %", "−8 %". */
    fun pct(x: Double): String { val r = x.roundToInt(); return (if (r > 0) "+" else if (r < 0) "−" else "") + "${kotlin.math.abs(r)} %" }
}
