package forma.core.run

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The line of a stadium timed from GPS, to a few tenths of a second.
 *
 * A fix alone is a metre or two off, and its error lasts a few seconds (measured on a real stadium run: 1.3 m, gone in
 * ~4 s), so the first fix near the line gives a lap ±2 s. Here the loop itself is known ([Oval], fitted to the runner's own
 * fixes after a lap, or kept from the last session there): a fix's place round it is how far round the runner is, the
 * error across the lane drops out, and a Kalman filter over that place, the speed the satellites give and the GPS error
 * that lingers, smoothed both ways over the last half minute, says when the line was crossed. A crossing is given a few
 * seconds after it ([LAG_MS]), when the fixes after it are in. Simulated with the error measured on the real run, a
 * crossing is off by about ±0.25 s (95 % within half a second) at running speed; a lap by about ±0.25 s.
 *
 * The same smoothed speed tells a run-up ([runUp]): a start from standing, to about ±0.2 s.
 */
class LoopTimer(private val lapMeters: Int?) {
    /** The loop, once fitted or given ([setGround]). */
    var oval: Oval? = null
        private set
    private var plane: Plane? = null

    /** The line's place on the loop (metres round it), once both are known. */
    private var lineArc: Double? = null
    private var line: Pair<Double, Double>? = null

    /** Fixes kept for fitting the loop (and to start the filter from once it is fitted): time (s), x, y, accuracy, speed. */
    private val kept = ArrayDeque<DoubleArray>()
    private var lastFit = Double.NEGATIVE_INFINITY

    /** A loop from the last session, not yet seen to be this one: dropped when the runner is plainly not on it. */
    private var given = false
    private var givenChecks = 0
    private var givenOff = 0

    /** +1 when the runner goes round the way the arc counts, −1 the other way; 0 not known yet. */
    private var dir = 0

    /** Metres round the loop added to the arc (with [dir]) to make the progress, kept across a restart of the filter. */
    private var unwrap = 0.0
    private var lastArc: Double? = null

    /** Filter steps of the last [KEEP_S] seconds. */
    private val steps = ArrayList<Step>()
    private var offSince: Double? = null

    /** Index of the last line crossing given (the laps are numbered round the loop from the line). */
    private var lastK: Long? = null
    private val out = ArrayList<Cross>()

    /** The smoothed progress and speed at the steps of the window, newest last (see [smooth]). */
    private var smoothed: List<Pair<Double, DoubleArray>> = emptyList()

    /**
     * A crossing of the line: when (active ms), how fast the runner went (m/s), and the lap's number round the loop.
     * [rough]: found across a gap in the fixes (off the loop for a while): the lap is there, its time only roughly.
     */
    data class Cross(val activeMs: Long, val speed: Double, val index: Long, val rough: Boolean = false)

    private class Step(val t: Double, val x: DoubleArray, val c: DoubleArray, val xp: DoubleArray, val cp: DoubleArray, val dt: Double)

    /** The loop as kept with the run and the settings (see [Oval.toGround]); null until fitted. */
    fun ground(): List<Double>? = oval?.let { o -> plane?.let { o.toGround(it) } }

    /** The loop from the last session on this stadium: crossings are timed from the first lap. */
    fun setGround(g: List<Double>?) {
        val (o, p) = Oval.fromGround(g) ?: return
        if (oval != null) return
        oval = o
        plane = p
        given = true
        placeLine()
    }

    /** The line (lat, lon): the start, the presses on it. Laps are numbered from it; one given later counts from then. */
    fun setLine(lat: Double, lon: Double) {
        line = lat to lon
        placeLine()
    }

    private fun placeLine() {
        val o = oval ?: return
        val p = plane ?: return
        val (lat, lon) = line ?: return
        lineArc = o.arc(p.x(lon), p.y(lat))
        // The laps count from now on: the progress so far is the lap being run.
        lastK = smoothed.lastOrNull()?.let { k(it.second[0]) }
    }

    /**
     * The loop's length as the runner runs it (his lane, not the kerb the stadium is measured at), from the last fixes
     * of this run fitted afresh; null before two laps of it. A lap set 230 m on a loop of 207 m makes every lap's target
     * a tenth too slow.
     */
    fun lapLength(): Double? {
        if (loopsSeen() < 2.0) return null
        val good = kept.filter { it[3] <= FIT_ACCURACY && (it[4] < 0 || it[4] >= 1.0) }
        val o = Oval.fit(DoubleArray(good.size) { good[it][1] }, DoubleArray(good.size) { good[it][2] }) ?: return null
        return o.perimeter.takeIf { lapMeters == null || it in lapMeters * 0.6..lapMeters * 1.5 }
    }

    /** Laps the filter has followed the runner round the loop in this run. */
    fun loopsSeen(): Double {
        val o = oval ?: return 0.0
        val first = firstProgress ?: return 0.0
        return abs(lastProgress - first) / o.perimeter
    }

    private var firstProgress: Double? = null
    private var lastProgress = 0.0

    /** Fixes in a row too far from where the filter expected them: the filter is lost, it starts again. */
    private var gated = 0

    /** Crossings are being timed: the loop, the line on it and the way round are known. */
    fun ready(): Boolean = following() && lineArc != null

    /** The runner is followed round the loop (a run-up can be found), the line known or not. */
    fun following(): Boolean = oval != null && dir != 0 && smoothed.isNotEmpty()

    /** Line crossings found since the last call, oldest first. */
    fun crossings(): List<Cross> = out.toList().also { out.clear() }

    /** The run paused: the filter starts again on the way back (the runner may have walked anywhere meanwhile). */
    fun pause() {
        // A crossing just before is given now (no fixes after it are coming), and the last state kept: one in the gap
        // till the filter is back is found between the two.
        find(Double.MAX_VALUE)
        smoothed.lastOrNull()?.let { tail = it }
        steps.clear()
        smoothed = emptyList()
        offSince = null
    }

    /** The last smoothed state before the filter stopped (see [pause]). */
    private var tail: Pair<Double, DoubleArray>? = null

    fun fix(activeMs: Long, lat: Double, lon: Double, accuracy: Float, speed: Float?) {
        if (accuracy > MAX_ACCURACY) return
        val t = activeMs / 1000.0
        val pl = plane ?: Plane(lat, lon).also { plane = it }
        val x = pl.x(lon)
        val y = pl.y(lat)
        val v = speed?.toDouble()?.takeIf { it in 0.0..12.0 }
        if (steps.isNotEmpty() && t <= steps.last().t) return
        kept.addLast(doubleArrayOf(t, x, y, accuracy.toDouble(), v ?: -1.0))
        while (kept.size > KEEP_FIXES) kept.removeFirst()
        val o = oval ?: return tryFit(t)
        if (given && !stillGiven(o, x, y, v)) return
        step(o, t, x, y, accuracy.toDouble(), v)
        smooth()
        find(t)
        refine(t)
    }

    /** Laps seen when the loop was last fitted again (see [refine]). */
    private var refinedAt = 0.0

    /** The runner ran off the loop: fitted again at the next chance. */
    private var refitSoon = false

    /**
     * After 2 and 5 laps the loop is fitted again from all the fixes kept: one lap of poor GPS can put the first fit a few
     * metres off, and then fixes fall off it. A loop that moved is taken mid-lap (far from the line), the laps keeping
     * their numbers.
     */
    private fun refine(t: Double) {
        val seen = loopsSeen()
        val due = if (refinedAt < 2.0) 2.0 else if (refinedAt < 5.0) 5.0 else Double.MAX_VALUE
        if (!refitSoon && (seen < due || given)) return
        val o = oval ?: return
        val pm = o.perimeter
        val la = lineArc
        val now = smoothed.lastOrNull() ?: return
        // Mid-lap: half a lap from the line either way, so the lap's number cannot slip at the switch.
        if (la != null && abs(wrap(now.second[0] - dir * la - pm / 2, pm)) > pm / 4) return
        if (!refitSoon) refinedAt = due
        refitSoon = false
        val good = kept.filter { it[3] <= FIT_ACCURACY && (it[4] < 0 || it[4] >= 1.0) }
        val n = Oval.fit(DoubleArray(good.size) { good[it][1] }, DoubleArray(good.size) { good[it][2] }) ?: return
        if (lapMeters != null && n.perimeter !in lapMeters * 0.6..lapMeters * 1.5) return
        val (px, py) = o.point(0.0)
        val moved = abs(n.perimeter - pm) > 1.0 || abs(n.off(px, py)) > 1.0 || abs(n.cx - o.cx) + abs(n.cy - o.cy) > 1.5
        if (!moved) return
        val lap = la?.let { k(now.second[0]) }
        val last = kept.last()
        find(Double.MAX_VALUE)
        steps.clear()
        smoothed = emptyList()
        tail = null
        offSince = null
        oval = n
        placeLine()
        // The progress on the new loop: the same lap, the place on it now.
        val arc = n.arc(last[1], last[2])
        val g0 = dir * (lineArc ?: 0.0)
        val phase = ((dir * arc - g0) % n.perimeter + n.perimeter) % n.perimeter
        val p = g0 + (lap ?: 0L) * n.perimeter + phase
        unwrap = p - dir * arc
        lastArc = arc
        firstProgress = firstProgress?.let { f -> p - (lastProgress - f) * n.perimeter / pm }
        given = false
        lastProgress = p
        lastK = lap
        step(n, t, last[1], last[2], last[3], last[4].takeIf { it >= 0 })
        smooth()
    }

    /** The loop given from the last session must be where the runner runs: half the first fixes far off it — another one. */
    private fun stillGiven(o: Oval, x: Double, y: Double, v: Double?): Boolean {
        if (givenChecks >= 30 || (v ?: 0.0) < 1.5) return true
        givenChecks++
        if (abs(o.off(x, y)) > Oval.NEAR_M) givenOff++
        if (givenChecks >= 20 && givenOff * 2 > givenChecks) {
            oval = null
            given = false
            lineArc = null
            pause()
            dir = 0
            return false
        }
        return true
    }

    /** Every [FIT_EVERY_S]: a loop from the fixes kept, once there are enough of them going round. */
    private fun tryFit(t: Double) {
        if (t - lastFit < FIT_EVERY_S) return
        val good = kept.filter { it[3] <= FIT_ACCURACY && (it[4] < 0 || it[4] >= 1.0) }
        if (good.size < 60) return
        lastFit = t
        val o = Oval.fit(DoubleArray(good.size) { good[it][1] }, DoubleArray(good.size) { good[it][2] }) ?: return
        // A loop far from the lap the person set is not the stadium (a park path, streets): no laps from it.
        if (lapMeters != null && o.perimeter !in lapMeters * 0.6..lapMeters * 1.5) return
        oval = o
        placeLine()
        // The filter starts from the last half minute of fixes: the laps of it are timed too.
        for (f in kept.filter { it[0] >= t - KEEP_S }) {
            step(o, f[0], f[1], f[2], f[3], f[4].takeIf { it >= 0 })
        }
        smooth()
        lastK = lineArc?.let { smoothed.firstOrNull()?.let { s -> k(s.second[0]) } }
        find(t)
    }

    /** Lap number at progress [p]: laps counted round the loop from the line. */
    private fun k(p: Double): Long {
        val o = oval ?: return 0
        val g0 = dir * (lineArc ?: 0.0)
        return floor((p - g0) / o.perimeter).toLong()
    }

    private fun step(o: Oval, t: Double, x: Double, y: Double, acc: Double, v: Double?) {
        // On the loop: within a few metres of it, more when the phone says its fixes are worse (never across the field).
        val near = abs(o.off(x, y)) <= (2.5 * acc).coerceIn(Oval.NEAR_M, 12.0)
        if (!near) {
            // Off the loop a while (a walk to the bench, across the field): the filter starts again when back on it.
            val since = offSince ?: t.also { offSince = it }
            if (t - since > OFF_RESET_S) {
                // Off it while running: the loop itself may be off (fitted on a lap of poor GPS) — fitted again soon.
                if ((v ?: 0.0) >= 1.5) refitSoon = true
                pause()
            }
            return
        }
        offSince = null
        val arc = o.arc(x, y)
        val p = o.perimeter
        if (dir == 0) {
            // The way round: from the fixes kept, how the arc moved while the runner moved.
            var sum = 0.0
            var prev: Double? = null
            for (f in kept.toList().takeLast(30)) {
                if (abs(o.off(f[1], f[2])) > Oval.NEAR_M) continue
                val a = o.arc(f[1], f[2])
                prev?.let { sum += wrap(a - it, p) }
                prev = a
            }
            if (abs(sum) < 10.0) return
            dir = if (sum > 0) 1 else -1
        }
        val last = steps.lastOrNull()
        if (last == null) {
            // On the loop again: the progress goes on from where it was (nearest), so the laps keep their numbers.
            val la = lastArc
            unwrap = if (la == null) 0.0 else unwrap + dir * wrap(arc - la, p) - dir * (arc - la)
            lastArc = arc
            val z = dir * arc + unwrap
            if (firstProgress == null) firstProgress = z
            lastProgress = z
            gated = 0
            val x0 = doubleArrayOf(z, v ?: 0.0, 0.0)
            val c0 = doubleArrayOf(SIG_B * SIG_B + r(acc), 0.0, 0.0, 0.0, if (v != null) SIG_DOP * SIG_DOP else 9.0, 0.0, 0.0, 0.0, SIG_B * SIG_B)
            steps += Step(t, x0, c0, x0, c0, 0.0)
            return
        }
        lastArc?.let { la -> unwrap += dir * (wrap(arc - la, p) - (arc - la)) }
        lastArc = arc
        val z = dir * arc + unwrap
        val dt = t - last.t
        val a = exp(-dt / TAU)
        // Predict: the speed holds (the runner's change of pace is the noise), the GPS error fades with TAU.
        val xp = doubleArrayOf(last.x[0] + dt * last.x[1], last.x[1], a * last.x[2])
        val f = doubleArrayOf(1.0, dt, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, a)
        val q = doubleArrayOf(
            QA * dt * dt * dt / 3, QA * dt * dt / 2, 0.0,
            QA * dt * dt / 2, QA * dt, 0.0,
            0.0, 0.0, SIG_B * SIG_B * (1 - a * a),
        )
        val cp = add(mul(mul(f, last.c), tr(f)), q)
        var x1 = xp.copyOf()
        var c1 = cp.copyOf()
        // The place: progress plus the lingering GPS error; a fix far off the filter's guess (a jump) is left out.
        val hz = doubleArrayOf(1.0, 0.0, 1.0)
        val s = quad(hz, c1) + r(acc)
        val inn = z - (x1[0] + x1[2])
        if (abs(inn) <= 4 * sqrt(s)) {
            update(x1, c1, hz, inn, s).let { (nx, nc) -> x1 = nx; c1 = nc }
            gated = 0
        } else if (++gated >= 3) {
            pause()
            return
        }
        // The satellites' speed: how fast, not which way — taken the way the runner is going (a step back at the line is
        // no step forward); slow and undecided, left out.
        // Standing: ~0 either way. Running: forward (nobody runs a rep backwards). Walking: only when the places already
        // say which way (a walk back to the line after a rep is no walk on).
        val way = when {
            v == null -> 0.0
            v < 0.4 -> 1.0
            v >= 1.8 -> if (x1[1] < -1.0) -1.0 else 1.0
            abs(x1[1]) > 0.6 -> if (x1[1] > 0) 1.0 else -1.0
            else -> 0.0
        }
        if (v != null && way != 0.0) {
            val hv = doubleArrayOf(0.0, 1.0, 0.0)
            val sv = quad(hv, c1) + SIG_DOP * SIG_DOP
            update(x1, c1, hv, way * v - x1[1], sv).let { (nx, nc) -> x1 = nx; c1 = nc }
        }
        steps += Step(t, x1, c1, xp, cp, dt)
        lastProgress = x1[0]
        while (steps.size > 2 && t - steps.first().t > KEEP_S) steps.removeAt(0)
    }

    /** Rauch–Tung–Striebel over the steps kept: each state with the fixes after it too. */
    private fun smooth() {
        val n = steps.size
        if (n == 0) {
            smoothed = emptyList()
            return
        }
        val xs = arrayOfNulls<DoubleArray>(n)
        xs[n - 1] = steps[n - 1].x
        for (i in n - 2 downTo 0) {
            val s = steps[i]
            val nx = steps[i + 1]
            val a = exp(-nx.dt / TAU)
            val f = doubleArrayOf(1.0, nx.dt, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, a)
            val inv = inv3(nx.cp) ?: run { xs[i] = s.x; null } ?: continue
            val g = mul(mul(s.c, tr(f)), inv)
            val d = DoubleArray(3) { xs[i + 1]!![it] - nx.xp[it] }
            xs[i] = DoubleArray(3) { r -> s.x[r] + g[r * 3] * d[0] + g[r * 3 + 1] * d[1] + g[r * 3 + 2] * d[2] }
        }
        smoothed = steps.indices.map { steps[it].t to xs[it]!! }
    }

    /** The next crossings of the line in the smoothed window, once [LAG_MS] old. */
    private fun find(now: Double) {
        val o = oval ?: return
        val la = lineArc ?: return
        // The state before a stop of the filter: a crossing in the gap till the first one after is found between the two.
        val before = tail?.takeIf { tl -> smoothed.firstOrNull()?.let { it.first - tl.first <= MAX_LOST_S } == true }
        if (before == null && smoothed.isNotEmpty()) tail = null
        val pts = listOfNotNull(before) + smoothed
        if (pts.size < 2) return
        val pm = o.perimeter
        val g0 = dir * la
        var k = (lastK ?: k(pts.first().second[0])) + 1
        while (true) {
            val g = g0 + k * pm
            val i = (0 until pts.size - 1).firstOrNull { pts[it].second[0] < g && pts[it + 1].second[0] >= g } ?: run {
                // Passed before the window began (a long gap): that lap is lost, the next one counts.
                if (pts.first().second[0] >= g) {
                    lastK = k
                    k++
                    null
                } else return
            } ?: continue
            val (t0, s0) = pts[i]
            val (t1, s1) = pts[i + 1]
            // A short gap, or a longer one at a steady run (the pace the same both sides): timed as any. A long one: the lap
            // is there, its time by the share of the way — roughly.
            val gap = t1 - t0
            val steady = s0[1] > 1.5 && abs(s1[1] - s0[1]) < 0.8
            val rough = gap > MAX_GAP_S && !(steady && gap <= MAX_STEADY_GAP_S)
            val tc = if (rough) t0 + (g - s0[0]) / (s1[0] - s0[0]) * gap else cross(t0, s0, t1, s1, g)
            if (tc > now - LAG_MS / 1000.0) return
            out += Cross((tc * 1000).toLong(), (s0[1] + s1[1]) / 2, k, rough)
            lastK = k
            k++
        }
    }

    /** When the progress between two smoothed states reached [g]: a cubic through their places and speeds. */
    private fun cross(t0: Double, s0: DoubleArray, t1: Double, s1: DoubleArray, g: Double): Double {
        val h = t1 - t0
        fun pos(t: Double): Double {
            val u = (t - t0) / h
            val u2 = u * u
            val u3 = u2 * u
            return (2 * u3 - 3 * u2 + 1) * s0[0] + (u3 - 2 * u2 + u) * h * s0[1] + (-2 * u3 + 3 * u2) * s1[0] + (u3 - u2) * h * s1[1]
        }
        var lo = t0
        var hi = t1
        repeat(40) {
            val m = (lo + hi) / 2
            if (pos(m) < g) lo = m else hi = m
        }
        return (lo + hi) / 2
    }

    /**
     * A run-up after [sinceMs]: the runner went from slow (standing, walking, jogging) to [fast] m/s or more and kept it at
     * least [HOLD_S]. Its start (active ms): where the speed began to rise — from the satellites' own speeds, fitted with
     * "steady, a straight rise, steady" (the smoothed speed spreads a sharp start over seconds both ways, so it would put
     * the start half a second early). Null when there is none yet.
     */
    fun runUp(sinceMs: Long, fast: Double): Long? {
        val s = smoothed
        if (s.size < 4) return null
        val since = sinceMs / 1000.0
        val now = s.last().first
        // The last rise through [fast], held since.
        var j = -1
        for (i in s.size - 1 downTo 1) {
            if (s[i].second[1] < fast * 0.85) break
            if (s[i - 1].second[1] < fast && s[i].second[1] >= fast) {
                j = i
                break
            }
        }
        if (j < 0 || now - s[j].first < HOLD_S || s[j].first < since - 5) return null
        val tj = s[j].first
        val before = s.filter { it.first in (tj - 6.0)..tj }
        val lo = before.minOf { it.second[1] }
        val rise = fast - lo
        if (rise < 1.2) return null
        fun at(level: Double): Double? {
            for (i in j downTo 1) {
                val a = s[i - 1].second[1]
                val b = s[i].second[1]
                if (a < level && b >= level) return s[i - 1].first + (level - a) / (b - a) * (s[i].first - s[i - 1].first)
            }
            return null
        }
        val t1 = at(lo + 0.25 * rise) ?: return null
        val t2 = at(lo + 0.75 * rise) ?: return null
        fitRise(tj)?.let { return (it * 1000).toLong() }
        if (t2 <= t1) return ((t1 - 0.3) * 1000).toLong()
        val accel = 0.5 * rise / (t2 - t1)
        return ((t1 - 0.25 * rise / accel) * 1000).toLong()
    }

    /**
     * The start of the rise through time [tj] from the satellites' speeds around it: steady before (standing or a jog),
     * a straight rise of 0.3–4 s, steady after; least squares over the start and the rise's length. Null without enough
     * speeds (a phone that gives none).
     */
    private fun fitRise(tj: Double): Double? {
        val pts = kept.filter { it[4] >= 0 && it[0] in (tj - 7.0)..(tj + 5.0) }.map { it[0] to it[4] }
        if (pts.size < 6) return null
        val before = pts.filter { it.first <= tj - 3.0 }.map { it.second }.sorted()
        val after = pts.filter { it.first >= tj + 1.0 }.map { it.second }.sorted()
        if (before.isEmpty() || after.size < 2) return null
        val lo = before[before.size / 2]
        val hi = after[after.size / 2]
        if (hi - lo < 1.2) return null
        var best = Double.MAX_VALUE
        var start: Double? = null
        var t0 = tj - 6.0
        while (t0 <= tj) {
            var len = 0.3
            while (len <= 4.0) {
                var cost = 0.0
                for ((t, v) in pts) {
                    val m = when {
                        t <= t0 -> lo
                        t >= t0 + len -> hi
                        else -> lo + (hi - lo) * (t - t0) / len
                    }
                    cost += (v - m) * (v - m)
                }
                if (cost < best) {
                    best = cost
                    start = t0
                }
                len += 0.05
            }
            t0 += 0.02
        }
        return start
    }

    /** The smoothed speed along the loop at the newest state, m/s (null when the filter is not running). */
    fun speed(): Double? = smoothed.lastOrNull()?.second?.get(1)

    private fun r(acc: Double) = SIG_WHITE * SIG_WHITE + max(acc - 1.5, 0.0).let { it * it } * 0.5

    companion object {
        /** A crossing is given this long after it: the fixes after it smooth it. */
        const val LAG_MS = 5_000L
        private const val KEEP_S = 30.0
        private const val KEEP_FIXES = 900
        private const val FIT_EVERY_S = 10.0
        private const val FIT_ACCURACY = 12.0
        private const val MAX_ACCURACY = 20f
        private const val OFF_RESET_S = 5.0
        private const val MAX_GAP_S = 8.0
        private const val MAX_STEADY_GAP_S = 16.0
        private const val MAX_LOST_S = 60.0
        private const val HOLD_S = 2.0

        /** The filter's numbers, tuned on simulated runs with the GPS error of a real one (see the class). */
        private const val TAU = 4.0
        private const val SIG_B = 1.5
        private const val QA = 0.4
        private const val SIG_WHITE = 0.5
        private const val SIG_DOP = 0.2

        private fun wrap(d: Double, p: Double): Double = ((d + p / 2) % p + p) % p - p / 2

        private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> (0 until 3).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] } }
        private fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { a[it] + b[it] }
        private fun tr(a: DoubleArray) = DoubleArray(9) { a[it % 3 * 3 + it / 3] }
        private fun quad(h: DoubleArray, c: DoubleArray) = (0 until 3).sumOf { i -> (0 until 3).sumOf { j -> h[i] * c[i * 3 + j] * h[j] } }

        /** One scalar measurement: state and covariance after it (Joseph form, so the covariance stays symmetric). */
        private fun update(x: DoubleArray, c: DoubleArray, h: DoubleArray, inn: Double, s: Double): Pair<DoubleArray, DoubleArray> {
            val ch = DoubleArray(3) { i -> (0 until 3).sumOf { j -> c[i * 3 + j] * h[j] } }
            val k = DoubleArray(3) { ch[it] / s }
            val nx = DoubleArray(3) { x[it] + k[it] * inn }
            val nc = DoubleArray(9) { c[it] - k[it / 3] * ch[it % 3] }
            return nx to nc
        }

        private fun inv3(m: DoubleArray): DoubleArray? {
            val a = m[0]; val b = m[1]; val c = m[2]
            val d = m[3]; val e = m[4]; val f = m[5]
            val g = m[6]; val h = m[7]; val i = m[8]
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            if (abs(det) < 1e-12) return null
            return doubleArrayOf(
                (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
                (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
                (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
            )
        }
    }
}
