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

    /** 0 — no match; higher — better. Every typed word has to match something. */
    fun score(ex: Exercise, query: String): Int {
        val q = normalize(query)
        if (q.isEmpty()) return 1
        val f = fields(ex)
        // The whole query as a name or a synonym: an exact synonym ("присед") beats a name that merely starts so.
        var total = maxOf(
            if (f.nameText == q) 200 else 0,
            if (f.aliasTexts.any { it == q }) 150 else 0,
            if (f.nameText.startsWith(q)) 120 else 0,
            if (f.aliasTexts.any { it.startsWith(q) }) 90 else 0,
        )
        for (t in q.split(' ')) {
            val inName = f.name.maxOf { wordScore(t, it) }
            val inAlias = f.aliases.maxOfOrNull { wordScore(t, it) } ?: 0
            // Main muscles, equipment and movement count fully; muscles that only help, half.
            val inTags = maxOf(f.tags.maxOf { wordScore(t, it) } * 2, f.helpers.maxOfOrNull { wordScore(t, it) } ?: 0)
            val best = maxOf(inName * 3, inAlias * 2, inTags)
            if (best == 0) return 0
            total += best
        }
        return total
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
        val queries = listOf(query, fromLatinLayout(query)).map(::normalize).distinct()
        return candidates.mapNotNull { ex -> queries.maxOf { score(ex, it) }.takeIf { it > 0 }?.let { ex to it } }
            .sortedWith(compareByDescending<Pair<Exercise, Int>> { it.second }.thenBy { it.first.name.length })
            .map { it.first }
    }
}
