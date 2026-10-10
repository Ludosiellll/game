package forma.core.model

import kotlin.math.min

/**
 * Finding an exercise the way people type in a gym: any word order, word endings ("гантелями" finds "гантели"),
 * typos, slang and English ("присед", "становуха", "rdl"), muscles and equipment ("бицепс", "блок"), even a query
 * typed in the English keyboard layout ("ghbctl" is "присед").
 */
object ExerciseSearch {
    fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').replace(Regex("[^a-zа-я0-9]+"), " ").trim()

    private const val LATIN = "qwertyuiop[]asdfghjkl;'zxcvbnm,."
    private const val CYRILLIC = "йцукенгшщзхъфывапролджэячсмитьбю"

    /** "ghbctl" → "присед": the same keys in the Russian layout. */
    fun fromLatinLayout(s: String): String = s.lowercase().map { c -> LATIN.indexOf(c).let { if (it >= 0) CYRILLIC[it] else c } }.joinToString("")

    private val endings = listOf(
        "иями", "ями", "ами", "ого", "его", "ому", "ему", "ыми", "ими", "ой", "ей", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие",
        "ую", "юю", "ом", "ем", "ам", "ям", "ах", "ях", "ов", "ев", "а", "я", "ы", "и", "у", "ю", "е", "о", "ь",
    )

    /** A crude Russian stem: long words lose their ending so different forms meet. */
    fun stem(w: String): String {
        if (w.length < 5 || w.any { it in 'a'..'z' }) return w
        val e = endings.firstOrNull { w.endsWith(it) && w.length - it.length >= 4 } ?: return w
        return w.dropLast(e.length)
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** How well one typed word matches one word of the exercise: 0 — not at all. */
    private fun wordScore(t: String, w: String): Int {
        if (w == t) return 10
        if (w.startsWith(t)) return 8
        val st = stem(t)
        if (st.length >= 3 && w.startsWith(st)) return 7
        if (t.length >= 4) {
            val allowed = if (t.length >= 7) 2 else 1
            if (distance(t, w) <= allowed || distance(t, w.take(t.length)) <= allowed) return 4
        }
        return 0
    }

    private data class Fields(
        val name: List<String>, val aliases: List<String>, val tags: List<String>, val helpers: List<String>,
        val nameText: String, val aliasTexts: List<String>,
    )

    private fun words(xs: List<String>) = xs.flatMap { normalize(it).split(' ') }

    private fun fields(ex: Exercise): Fields {
        val name = normalize(ex.name)
        val aliases = ex.aliases.map(::normalize)
        val tags = words(ex.primary.map { it.ru } + ex.equipment.ru + ex.pattern.ru)
        return Fields(name.split(' '), aliases.flatMap { it.split(' ') }, tags, words(ex.secondary.map { it.ru }), name, aliases)
    }

    /** How well one typed word matches the exercise: 0 — not at all. */
    private fun wordMatch(f: Fields, t: String): Int {
        val inName = f.name.maxOf { wordScore(t, it) }
        val inAlias = f.aliases.maxOfOrNull { wordScore(t, it) } ?: 0
        // Main muscles, equipment and movement count fully; muscles that only help, half.
        val inTags = maxOf(f.tags.maxOf { wordScore(t, it) } * 2, f.helpers.maxOfOrNull { wordScore(t, it) } ?: 0)
        return maxOf(inName * 3, inAlias * 2, inTags)
    }

    /** 0 — no match; higher — better. Every typed word has to match something. */
    fun score(ex: Exercise, query: String): Int = score(fields(ex), normalize(query), emptySet())

    /** Like [score], but the [optional] words only add to it when they match; the rest have to. */
    /** The whole of [q] as a name or a synonym: an exact synonym ("присед") beats a name that merely starts so. */
    private fun whole(f: Fields, q: String): Int = maxOf(
        if (f.nameText == q) 200 else 0,
        if (f.aliasTexts.any { it == q }) 150 else 0,
        if (f.nameText.startsWith(q)) 120 else 0,
        if (f.aliasTexts.any { it.startsWith(q) }) 90 else 0,
    )

    private fun score(f: Fields, q: String, optional: Set<String>): Int {
        if (q.isEmpty()) return 1
        // The query as typed, or its words that matter ("отжимания с весом" is "отжимания").
        val core = q.split(' ').filter { it !in optional }.joinToString(" ")
        var total = maxOf(whole(f, q), if (core.isNotEmpty()) whole(f, core) else 0)
        var matched = false
        for (t in q.split(' ')) {
            // Little words ("в", "на", "со") help no exercise more than another: they never add to the score.
            if (t in optional && t.isShortWord()) continue
            val best = wordMatch(f, t)
            if (best == 0) {
                if (t in optional) continue
                return 0
            }
            matched = true
            total += best
        }
        return if (matched) total else 0
    }

    /** A Russian word of one or two letters: "в", "на", "со". */
    private fun String.isShortWord() = length <= 2 && all { it in 'а'..'я' }

    /** Words about the load ("со своим весом", "без веса", "с отягощением"): they say how, not which exercise. */
    private val LOAD_WORDS = setOf(
        "свой", "своим", "своего", "собственный", "собственным", "собственного", "собственном", "вес", "весом", "веса",
        "без", "отягощение", "отягощением", "отягощения", "доп", "дополнительный", "дополнительным", "дополнительного",
        "кг", "груз", "грузом", "утяжелитель", "утяжелителем", "утяжелителями", "утяжелителя", "пояс", "пояса", "поясом",
        "поясе", "блин", "блином", "цепь", "цепью", "цепями", "жилет", "жилетом",
    )

    /**
     * Words of [q] that need not match: short Russian words ("со", "в", "на"), words about the load, and words no
     * exercise in [pool] has. "Подтягивания со своим весом" finds the pull-ups; a query of such words alone ("свой
     * вес") still has to match.
     */
    private fun optionalWords(q: String, pool: List<Fields>): Set<String> {
        val words = q.split(' ')
        val optional = words.filter { t -> t.isShortWord() || t in LOAD_WORDS || pool.none { wordMatch(it, t) > 0 } }.toSet()
        return if (words.all { it in optional }) emptySet() else optional
    }

    /**
     * Exercises for [query], best first, among [pool] narrowed by [muscle] (main or helping) and [equipment].
     * An empty query lists everything by movement and name.
     */
    fun search(query: String, pool: List<Exercise> = Catalog.all, muscle: Muscle? = null, equipment: Equipment? = null): List<Exercise> {
        val candidates = pool.filter {
            (muscle == null || muscle in it.primary || muscle in it.secondary) && (equipment == null || it.equipment == equipment)
        }
        if (normalize(query).isEmpty()) return candidates.sortedWith(compareBy<Exercise> { it.pattern.ordinal }.thenBy { it.name })
        val all = pool.associateWith(::fields)
        // What is optional is judged on the whole pool: a real exercise word with a filter that rules it out finds nothing.
        var queries = listOf(query, fromLatinLayout(query)).map(::normalize).distinct().map { it to optionalWords(it, all.values.toList()) }
        fun find() = candidates.mapNotNull { ex -> queries.maxOf { (q, opt) -> score(all.getValue(ex), q, opt) }.takeIf { it > 0 }?.let { ex to it } }
        var found = find()
        // No exercise has every word ("подтягивания с гантелей"): the first words say which exercise, the last ones how —
        // let go of the last word, then the one before, while one is left.
        while (found.isEmpty()) {
            queries = queries.mapNotNull { (q, opt) ->
                val required = q.split(' ').filter { it !in opt }
                // Never down to filler alone ("со своим" finds nothing).
                val rest = required.dropLast(1)
                if (rest.none { !it.isShortWord() && it !in LOAD_WORDS }) null else q to opt + required.last()
            }
            if (queries.isEmpty()) break
            found = find()
        }
        return found.sortedWith(compareByDescending<Pair<Exercise, Int>> { it.second }.thenBy { it.first.name.length }).map { it.first }
    }
}
