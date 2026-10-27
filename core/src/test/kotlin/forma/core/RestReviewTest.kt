package forma.core

import forma.core.strength.DoneSet
import forma.core.strength.OneRm
import forma.core.strength.RestAdvice
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rest probes on one person's five logged gym sessions (13–27 Oct 2026): the rest learned from his sets
 * ([RestAdvice.pairsOf] / [RestAdvice.personal]) and the rest said after a set ([RestAdvice.after]).
 * Each failing assertion is one finding; the message carries the numbers.
 */
class RestReviewTest {
    /** One logged set: weight, reps, reserve (null — not rated), the clock it was logged at. */
    private data class S(val w: Double, val reps: Int, val rir: Int?, val at: String)

    /** One exercise of one session: the plan's rep target, the plan's rest, the sets in order. */
    private data class Session(val exercise: String, val day: String, val target: Int, val plannedRest: Int, val sets: List<S>)

    private fun ms(day: String, at: String) =
        LocalDate.parse(day).atTime(LocalTime.parse(at)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun Session.timed() = sets.map { DoneSet(it.w, it.reps, it.rir) to ms(day, it.at) }

    private val sessions = listOf(
        Session("chest_press", "2026-10-13", 8, 120, listOf(S(80.0, 10, 4, "10:58:35"), S(90.0, 9, 3, "11:02:20"), S(95.0, 8, 0, "11:06:16"))),
        Session("machine_shoulder_press", "2026-10-13", 5, 150, listOf(S(60.0, 10, 2, "11:13:35"), S(70.0, 6, 1, "11:17:43"), S(70.0, 6, 1, "11:21:27"))),
        Session("pullup", "2026-10-13", 5, 150, listOf(S(20.0, 10, 1, "11:30:31"), S(30.0, 5, 0, "11:35:25"), S(25.0, 6, 1, "11:42:29"))),
        Session("lateral_raise", "2026-10-13", 10, 75, listOf(S(12.5, 14, 5, "11:47:41"), S(15.0, 9, 2, "11:49:58"))),
        Session("hammer_curl", "2026-10-13", 10, 75, listOf(S(25.0, 8, null, "11:53:53"), S(22.5, 10, 1, "11:59:11"))),
        Session("cable_row", "2026-10-13", 8, 120, listOf(S(64.0, 10, 5, "12:05:25"), S(76.0, 8, 2, "12:07:41"), S(76.0, 7, 0, "12:11:22"))),
        Session("chest_press", "2026-10-15", 5, 150, listOf(S(90.0, 5, 4, "12:18:32"), S(90.0, 5, 5, "12:21:37"), S(95.0, 6, 3, "12:25:30"))),
        Session("pullup", "2026-10-15", 5, 150, listOf(S(25.0, 5, 4, "12:30:50"), S(25.0, 5, 5, "12:36:58"), S(30.0, 5, 3, "12:42:09"))),
        Session("db_bench", "2026-10-15", 6, 180, listOf(S(30.0, 8, 0, "12:49:23"), S(30.0, 6, 3, "12:55:39"))),
        Session("lat_pulldown", "2026-10-15", 8, 120, listOf(S(92.0, 7, 1, "13:01:02"), S(84.0, 8, 1, "13:04:05"))),
        Session("db_overhead_ext", "2026-10-15", 10, 75, listOf(S(24.0, 10, 5, "13:08:43"), S(27.5, 10, 3, "13:11:45"))),
        Session("machine_lateral", "2026-10-15", 10, 75, listOf(S(24.0, 10, 5, "13:15:07"), S(24.0, 10, 2, "13:17:31"))),
        Session("chest_press", "2026-10-22", 5, 150, listOf(S(100.0, 5, 4, "10:56:00"), S(105.0, 5, 3, "10:59:56"), S(105.0, 6, 3, "11:06:25"))),
        Session("pullup", "2026-10-22", 7, 150, listOf(S(25.0, 7, 3, "11:15:45"), S(25.0, 7, 2, "11:20:49"), S(25.0, 8, 0, "11:26:11"))),
        Session("db_bench", "2026-10-22", 6, 180, listOf(S(30.0, 6, 2, "11:32:37"), S(30.0, 6, 1, "11:37:37"))),
        Session("lat_pulldown", "2026-10-22", 8, 120, listOf(S(85.0, 8, 5, "11:42:56"), S(92.0, 8, 0, "11:46:42"))),
        // A 10-minute talk between the two sets.
        Session("db_overhead_ext", "2026-10-22", 10, 75, listOf(S(30.0, 10, 4, "11:51:19"), S(30.0, 10, 3, "12:01:41"))),
        Session("machine_lateral", "2026-10-22", 10, 75, listOf(S(24.0, 10, 2, "12:08:17"), S(24.0, 10, 2, "12:14:54"))),
        Session("squat", "2026-10-23", 5, 150, listOf(S(90.0, 5, 2, "12:20:26"), S(90.0, 5, 3, "12:24:12"), S(95.0, 5, 1, "12:29:51"))),
        // Three sets logged within 20 s after the fact.
        Session("leg_curl", "2026-10-23", 10, 75, listOf(S(70.0, 10, 5, "12:44:34"), S(80.0, 8, 5, "12:44:47"), S(90.0, 8, 2, "12:44:54"))),
        Session("leg_ext", "2026-10-23", 8, 120, listOf(S(70.0, 10, 4, "12:47:19"), S(80.0, 8, 3, "12:50:37"), S(80.0, 8, 2, "12:58:00"))),
        Session("machine_shoulder_press", "2026-10-27", 5, 150, listOf(S(70.0, 9, 4, "11:23:45"), S(75.0, 8, 2, "11:28:13"), S(75.0, 6, 2, "11:31:34"))),
        Session("pullup", "2026-10-27", 7, 150, listOf(S(25.0, 8, 3, "11:38:55"), S(30.0, 7, 0, "11:43:11"), S(30.0, 6, 0, "11:48:50"))),
        Session("chest_press", "2026-10-27", 8, 120, listOf(S(85.0, 8, 4, "11:58:09"), S(90.0, 8, 2, "12:01:07"), S(90.0, 8, 0, "12:05:43"))),
        Session("cable_row", "2026-10-27", 8, 120, listOf(S(64.0, 9, 5, "12:11:31"), S(72.0, 8, 5, "12:14:00"), S(76.0, 10, 4, "12:17:39"))),
        // Two sets logged 4 s apart after the fact.
        Session("hammer_curl", "2026-10-27", 10, 75, listOf(S(22.5, 10, 2, "12:26:15"), S(22.5, 9, 0, "12:26:19"))),
        Session("machine_lateral", "2026-10-27", 11, 75, listOf(S(24.0, 11, 2, "12:32:07"), S(24.0, 10, 0, "12:34:30"))),
    )

    private fun pairsOf(exercise: String): List<RestAdvice.Pair> =
        sessions.filter { it.exercise == exercise }.flatMap { RestAdvice.pairsOf(it.timed(), it.target) }

    private fun usable(pairs: List<RestAdvice.Pair>) = pairs.filter { it.restSec in 30..480 }

    // ---------- what works ----------

    /** Sets logged after the fact (3–20 s apart) and a 10-minute talk leave no pair the learning would use. */
    @Test fun afterTheFactLogsAndLongTalksAreFilteredOut() {
        val curl = sessions.first { it.exercise == "hammer_curl" && it.day == "2026-10-27" }
        val p = RestAdvice.pairsOf(curl.timed(), curl.target)
        assertEquals(1, p.size, "the two hammer-curl sets at the same weight make one pair")
        assertEquals(0, p[0].restSec, "logged 4 s apart: the rest comes out as 0 s and is dropped (not in 30..480)")
        assertEquals(0, RestAdvice.personal(List(6) { p[0] }, 75), "pairs with no real rest do not move the rest")
        val legCurl = sessions.first { it.exercise == "leg_curl" }
        assertEquals(0, RestAdvice.pairsOf(legCurl.timed(), legCurl.target).size, "three weights within 20 s: no pair at all")
        val ext = sessions.first { it.exercise == "db_overhead_ext" && it.day == "2026-10-22" }
        val talk = RestAdvice.pairsOf(ext.timed(), ext.target).single()
        assertTrue(talk.restSec > 480, "a 10-minute talk (${talk.restSec} s) is above the 480 s cap and dropped")
        assertEquals(0, RestAdvice.personal(List(6) { talk }, 75))
    }

    /** The plan's rests after the modifiers sit where the strength and hypertrophy evidence puts them. */
    @Test fun restsAfterASetSitInTheEvidenceWindow() {
        // Main lift, 5 reps with 2 in reserve: 150 + 20 (heavy) = 170 → 2:45. ACSM / Grgic 2018: ≥ 2 min for trained strength work.
        assertEquals(165, RestAdvice.after(150, 5, 2, 2, compound = true, deload = false, personal = 0).sec)
        // Main lift, 5 reps to failure: 150 + 45 + 20 = 215 → 3:30 (within the 2–5 min usually advised).
        assertEquals(210, RestAdvice.after(150, 5, 0, 2, compound = true, deload = false, personal = 0).sec)
        // Main lift, 8 reps with 2 in reserve: the base 2:30.
        assertEquals(150, RestAdvice.after(150, 8, 2, 2, compound = true, deload = false, personal = 0).sec)
        // Secondary, 8 reps, 1 in reserve as planned: 2:00 (Singer 2024: no clear gain past ~90 s for growth).
        assertEquals(120, RestAdvice.after(120, 8, 1, 1, compound = true, deload = false, personal = 0).sec)
        // Accessory, 12 reps, 2 in reserve: 1:15.
        assertEquals(75, RestAdvice.after(75, 12, 2, 2, compound = false, deload = false, personal = 0).sec)
        // A compound never below 1:30, never above 5:00.
        assertEquals(90, RestAdvice.after(120, 14, 5, 2, compound = true, deload = true, personal = -15).sec)
        assertEquals(300, RestAdvice.after(300, 3, 0, 2, compound = true, deload = false, personal = 60).sec)
    }

    // ---------- findings ----------

    /**
     * He ramps the weight inside an exercise (80 → 90 → 95, 60 → 70 → 70, 20 → 30 → 25): [RestAdvice.pairsOf] only pairs
     * two sets at the SAME weight, so after 27 exercise-sessions no exercise has the 6 pairs [RestAdvice.personal] waits
     * for. The learning is inert, not wrong. Pairing adjacent sets within 10 % of each other through the one-rep estimate
     * ([OneRm.rtfAt]) would have had it live on the chest press and the pull-ups after the fourth session.
     */
    @Test fun theRestLearningNeverStartsForALifterWhoRampsInsideAnExercise() {
        val byExercise = sessions.map { it.exercise }.distinct().associateWith { ex ->
            val pairs = usable(pairsOf(ex))
            val sessionsOf = sessions.count { it.exercise == ex }
            // The same pairing through the e1RM: each rated set and the next within 10 % of its weight.
            val crossWeight = sessions.filter { it.exercise == ex }.sumOf { s ->
                s.sets.zipWithNext().count { (a, b) ->
                    a.rir != null && b.rir != null && abs(a.w - b.w) <= 0.1 * a.w && run {
                        val rest = (ms(s.day, b.at) - ms(s.day, a.at)) / 1000 - b.reps * 3 - 10
                        rest in 30..480
                    }
                }
            }
            Triple(sessionsOf, pairs.size, crossWeight)
        }
        val table = byExercise.entries.joinToString("; ") { (ex, t) -> "$ex: ${t.first} sessions → ${t.second} same-weight pairs (${t.third} within 10 %)" }
        val live = byExercise.filterValues { it.second >= RestAdvice.MIN_PAIRS }.keys
        assertTrue(
            live.isNotEmpty(),
            "after 5 sessions (27 exercise-sessions) no exercise reaches MIN_PAIRS=${RestAdvice.MIN_PAIRS}: $table. " +
                "personal() returns 0 everywhere; at his rate (≤ 1 same-weight pair a session) the chest press needs ~3 more sessions, the pull-ups ~2.",
        )
    }

    /**
     * His 27.10 chest press: 90 × 8 with 2 left, then — after 4:36 on the clock (242 s net of the set, twice the planned
     * 2:00) — 90 × 8 with 0 left. His 27.10 shoulder press: 75 × 8 (2 left) → 173 s net → 75 × 6 (2 left), planned 2:30.
     * Reps lost after a rest far longer than planned say the weight was the limit, not the rest; [RestAdvice.personal]
     * still reads a median loss of 2 as "recovers slowly" and adds 30 s — the "−15" branch checks the rest taken against
     * the plan, the "+30 / +60" branches do not.
     */
    @Test fun repsLostAfterRestsTwiceThePlanAreNotReadAsSlowRecovery() {
        val chest = sessions.first { it.exercise == "chest_press" && it.day == "2026-10-27" }
        // The last two sets (90 × 8, 2 left → 90 × 8 to failure); the first pair is 85 → 90, carried over.
        val pair = RestAdvice.pairsOf(chest.timed(), chest.target).last()
        assertEquals(RestAdvice.Pair(242, 10, 8), pair)
        val shift = RestAdvice.personal(List(RestAdvice.MIN_PAIRS) { pair }, chest.plannedRest)
        assertEquals(
            0, shift,
            "six pairs like his 27.10 chest press (rest ${pair.restSec} s vs ${chest.plannedRest} s planned, ${pair.before} → ${pair.after} reps to failure) " +
                "make personal() = +$shift s: the rest was already 2× the plan, so more rest is not the answer. " +
                "The shoulder press pair (173 s vs 150 planned, 10 → 8) says the same.",
        )
    }

    /**
     * Six pairs at the longest rest the learning accepts (480 s = 8 min, 4× a secondary's plan) still read as "needs
     * longer rest": the window that keeps after-the-fact logs out has no upper guard for the plus side.
     */
    @Test fun eightMinuteRestsWithLostRepsStillAddThirtySeconds() {
        val pairs = List(6) { RestAdvice.Pair(480, 10, 8) }
        assertEquals(0, RestAdvice.personal(pairs, 120), "8-minute rests with 2 reps lost → +${RestAdvice.personal(pairs, 120)} s on a 2:00 plan")
    }

    /**
     * What the cross-weight pairing would say of him: his adjacent rated sets within 10 % of each other, each "before"
     * carried to the next set's weight through the one-rep estimate. Informational: the median loss is about one rep
     * either way, so the learning would land on "as planned" — not on longer rests.
     */
    @Test fun theCrossWeightPairingWouldSayHeRecoversAsPlanned() {
        val pairs = sessions.filter { it.exercise == "chest_press" }.flatMap { s ->
            s.sets.zipWithNext().mapNotNull { (a, b) ->
                if (a.rir == null || b.rir == null || abs(a.w - b.w) > 0.1 * a.w) return@mapNotNull null
                val rest = ((ms(s.day, b.at) - ms(s.day, a.at)) / 1000 - b.reps * 3 - 10).toInt()
                val before = OneRm.rtfAt(OneRm.estimate(a.w, a.reps, a.rir), b.w)
                RestAdvice.Pair(rest, Math.round(before).toInt(), b.reps + b.rir)
            }
        }
        assertTrue(pairs.size >= RestAdvice.MIN_PAIRS, "chest press: ${pairs.size} cross-weight pairs: $pairs")
        assertEquals(0, RestAdvice.personal(pairs, 150), "pairs $pairs")
    }
}
