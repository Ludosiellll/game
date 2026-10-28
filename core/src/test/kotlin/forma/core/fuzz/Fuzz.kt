package forma.core.fuzz

import java.io.File
import kotlin.random.Random

/**
 * Random cases checked against rules that must hold for anybody: a rule broken is a bug, whoever the person is.
 *
 * In the usual test run each rule sees [CASES] cases of a fixed seed (a few seconds, the same cases every time). In the
 * background run (`FORMA_FUZZ_SECONDS` per rule, `FORMA_FUZZ_SEED` new every time) each rule runs for its time on new
 * cases. A rule broken: the case is made as small as it stays broken ([check]'s `shrink`), written to `build/fuzz/` and
 * the test fails with the case, the rule and how to run that one case again:
 * `FORMA_FUZZ_SEED=… FORMA_FUZZ_ONLY=<rule> FORMA_FUZZ_CASE=<n>`.
 */
object Fuzz {
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    val seed: Long = env("FORMA_FUZZ_SEED")?.toLong() ?: 20261028L
    private val seconds: Long = env("FORMA_FUZZ_SECONDS")?.toLong() ?: 0
    private val only: String? = env("FORMA_FUZZ_ONLY")
    private val one: Long? = env("FORMA_FUZZ_CASE")?.toLong()

    /** Cases per rule in the usual test run. */
    const val CASES = 400

    /** Cases checked so far, by rule (the background run's report). */
    val counts = linkedMapOf<String, Long>()

    private val out = File("build/fuzz").also { it.mkdirs() }

    /** The case [i] of rule [name]: its own random numbers, the same for the same seed. */
    fun random(name: String, i: Long): Random = Random(seed * 1_000_003L + name.hashCode() * 7_919L + i)

    /**
     * Checks [rule] on cases made by [gen]: the rule returns what is wrong, or null. A case that throws breaks the rule
     * too. [shrink] gives simpler versions of a case (fewer sets, rounder numbers); the simplest one still broken is
     * reported. [show] writes a case so a person can read it.
     */
    fun <C> check(
        name: String,
        gen: (Random) -> C,
        shrink: (C) -> Sequence<C> = { emptySequence() },
        show: (C) -> String = { it.toString() },
        cases: Int = CASES,
        rule: (C) -> String?,
    ) {
        if (only != null && only != name) return
        fun broken(c: C): String? = try {
            rule(c)
        } catch (e: Throwable) {
            "${e::class.simpleName}: ${e.message}\n" + e.stackTrace.take(8).joinToString("\n") { "    at $it" }
        }
        val until = System.currentTimeMillis() + seconds * 1000
        var i = one ?: 0L
        var n = 0L
        while (true) {
            val c = gen(random(name, i))
            val why = broken(c)
            n++
            if (why != null) {
                // The simplest version still broken: each step takes the first simpler case that breaks the rule too.
                var small = c
                var smallWhy = why
                var tries = 0
                var better = true
                while (better && tries < 2000) {
                    better = false
                    for (s in shrink(small)) {
                        tries++
                        val w = broken(s) ?: continue
                        small = s
                        smallWhy = w
                        better = true
                        break
                    }
                }
                val text = buildString {
                    appendLine("Правило «$name» нарушено.")
                    appendLine("Повторить: FORMA_FUZZ_SEED=$seed FORMA_FUZZ_ONLY=$name FORMA_FUZZ_CASE=$i")
                    appendLine()
                    appendLine("Что не так: $smallWhy")
                    appendLine()
                    appendLine("Простейший случай:")
                    appendLine(show(small))
                    if (small !== c) {
                        appendLine()
                        appendLine("Исходный случай ($why):")
                        appendLine(show(c))
                    }
                }
                File(out, "$name.txt").writeText(text)
                throw AssertionError(text)
            }
            counts[name] = (counts[name] ?: 0) + 1
            i++
            if (one != null) break
            if (seconds > 0) {
                if (System.currentTimeMillis() >= until) break
            } else if (n >= cases) {
                break
            }
        }
        File(out, "counts.txt").appendText("$name\t$n\tseed=$seed\n")
    }

    // ---------- helpers for generators ----------

    fun <T> Random.pick(xs: List<T>): T = xs[nextInt(xs.size)]
    fun <T> Random.pick(vararg xs: T): T = xs[nextInt(xs.size)]
    fun Random.chance(p: Double): Boolean = nextDouble() < p
    fun Random.range(a: Double, b: Double): Double = a + nextDouble() * (b - a)

    /** Sequence of [list] without one element at a time (the shrink of a list). */
    fun <T> withoutOne(list: List<T>): Sequence<List<T>> = list.indices.asSequence().map { k -> list.filterIndexed { i, _ -> i != k } }
}
