package forma.core.run

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Metres east ([x]) and north ([y]) of ([lat0], [lon0]): flat enough for a stadium and the streets round it. */
class Plane(val lat0: Double, val lon0: Double) {
    private val perLat = 111_132.954 - 559.822 * cos(2 * Math.toRadians(lat0)) + 1.175 * cos(4 * Math.toRadians(lat0))
    private val perLon = 111_412.84 * cos(Math.toRadians(lat0)) - 93.5 * cos(3 * Math.toRadians(lat0))

    fun x(lon: Double) = (lon - lon0) * perLon
    fun y(lat: Double) = (lat - lat0) * perLat
    fun lat(y: Double) = lat0 + y / perLat
    fun lon(x: Double) = lon0 + x / perLon
}

/**
 * A stadium's loop as the runner runs it, in metres on a [Plane]: two straights of 2·[half] joined by two half circles
 * of [radius], centred at ([cx], [cy]), the straights along [angle] (radians from east). Fitted to the runner's own GPS
 * fixes ([fit]), so it is his lane, not the kerb. A fix's place on it ([arc]) says how far round the runner is: GPS
 * scatters a metre or two across the lane, and that does not move the place.
 *
 * The arc counts anticlockwise from the middle of the straight that is on the right of [angle]'s direction.
 */
data class Oval(val cx: Double, val cy: Double, val angle: Double, val half: Double, val radius: Double) {
    val perimeter: Double get() = 4 * half + 2 * PI * radius

    private val c = cos(angle)
    private val s = sin(angle)

    private fun u(x: Double, y: Double) = (x - cx) * c + (y - cy) * s
    private fun v(x: Double, y: Double) = -(x - cx) * s + (y - cy) * c

    /** Signed distance from the loop: positive outside it, negative inside. */
    fun off(x: Double, y: Double): Double {
        val u = abs(u(x, y))
        val v = v(x, y)
        return if (u <= half) abs(v) - radius else hypot(u - half, v) - radius
    }

    /** Metres round the loop (0 until [perimeter]) of the point of it nearest to ([x], [y]). */
    fun arc(x: Double, y: Double): Double {
        val u = u(x, y)
        val v = v(x, y)
        val bend = PI * radius
        val a = when {
            u > half -> half + radius * (atan2(v, u - half) + PI / 2)
            u < -half -> {
                var ang = atan2(v, u + half)
                if (ang < 0) ang += 2 * PI
                3 * half + bend + radius * (ang - PI / 2)
            }
            v < 0 -> u
            else -> half + bend + (half - u)
        }
        return ((a % perimeter) + perimeter) % perimeter
    }

    /** The point of the loop [arc] metres round it. */
    fun point(arc: Double): Pair<Double, Double> {
        val p = perimeter
        val a = ((arc % p) + p) % p
        val bend = PI * radius
        val (u, v) = when {
            a < half -> a to -radius
            a < half + bend -> (-PI / 2 + (a - half) / radius).let { half + radius * cos(it) to radius * sin(it) }
            a < 3 * half + bend -> half - (a - half - bend) to radius
            a < 3 * half + 2 * bend -> (PI / 2 + (a - 3 * half - bend) / radius).let { -half + radius * cos(it) to radius * sin(it) }
            else -> a - p to -radius
        }
        return cx + u * c - v * s to cy + u * s + v * c
    }

    /** Whether [arc] is on a bend. */
    fun onBend(arc: Double): Boolean {
        val p = perimeter
        val a = ((arc % p) + p) % p
        return a in half..(half + PI * radius) || a in (3 * half + PI * radius)..(3 * half + 2 * PI * radius)
    }

    /** Kept with the run and the settings: the centre on the ground, then the shape (see [fromGround]). */
    fun toGround(plane: Plane): List<Double> = listOf(plane.lat(cy), plane.lon(cx), angle, half, radius)

    companion object {
        /** Fixes this far off the loop are not on it (a step off the track, a jump of GPS). */
        const val NEAR_M = 8.0

        fun fromGround(g: List<Double>?): Pair<Oval, Plane>? {
            if (g == null || g.size != 5 || g.any { !it.isFinite() }) return null
            val plane = Plane(g[0], g[1])
            val o = Oval(0.0, 0.0, g[2], g[3], g[4])
            return if (o.sane()) o to plane else null
        }

        /**
         * The loop through the points ([xs], [ys], metres): least squares of the distances to it, with the points far off
         * left out (a run to the bench, a jump of GPS). Null when they do not make a stadium's loop: too few, not round
         * the whole of it, or too far from it.
         */
        fun fit(xs: DoubleArray, ys: DoubleArray): Oval? {
            if (xs.size < 40 || xs.size != ys.size) return null
            var keep = BooleanArray(xs.size) { true }
            var o = start(xs, ys) ?: return null
            repeat(5) {
                o = solve(xs, ys, keep, o) ?: return null
                val d = DoubleArray(xs.size) { abs(o.off(xs[it], ys[it])) }
                val mad = d.sorted()[d.size / 2] * 1.4826
                val cut = max(3 * mad, 3.5)
                keep = BooleanArray(xs.size) { d[it] <= cut }
            }
            return o.takeIf { it.sane() && it.covers(xs, ys, keep) }
        }

        /** From the spread of the points: the long axis, the length and the width. */
        private fun start(xs: DoubleArray, ys: DoubleArray): Oval? {
            val n = xs.size
            val mx = xs.average()
            val my = ys.average()
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - mx
                val dy = ys[i] - my
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }
            val angle = 0.5 * atan2(2 * sxy, sxx - syy)
            val c = cos(angle)
            val s = sin(angle)
            val us = DoubleArray(n) { (xs[it] - mx) * c + (ys[it] - my) * s }.sorted()
            val vs = DoubleArray(n) { -(xs[it] - mx) * s + (ys[it] - my) * c }.sorted()
            val len = (us[(n * 0.98).toInt().coerceAtMost(n - 1)] - us[(n * 0.02).toInt()]) / 2
            val wid = (vs[(n * 0.98).toInt().coerceAtMost(n - 1)] - vs[(n * 0.02).toInt()]) / 2
            if (wid < 3.0) return null
            return Oval(mx, my, angle, max(len - wid, 1.0), wid)
        }

        /** Levenberg–Marquardt on the five numbers, the points in [keep]. */
        private fun solve(xs: DoubleArray, ys: DoubleArray, keep: BooleanArray, from: Oval): Oval? {
            val idx = keep.indices.filter { keep[it] }
            if (idx.size < 30) return null
            fun of(p: DoubleArray) = Oval(p[0], p[1], p[2], max(p[3], 0.0), p[4])
            fun res(p: DoubleArray): DoubleArray = of(p).let { o -> DoubleArray(idx.size) { o.off(xs[idx[it]], ys[idx[it]]) } }
            var p = doubleArrayOf(from.cx, from.cy, from.angle, from.half, from.radius)
            var r = res(p)
            var cost = r.sumOf { it * it }
            var lambda = 1e-2
            repeat(60) {
                val jac = Array(5) { k ->
                    val h = 1e-5 * max(1.0, abs(p[k]))
                    val q = p.copyOf().also { it[k] += h }
                    val rq = res(q)
                    DoubleArray(idx.size) { (rq[it] - r[it]) / h }
                }
                val a = Array(5) { i -> DoubleArray(5) { j -> (idx.indices).sumOf { jac[i][it] * jac[j][it] } } }
                val g = DoubleArray(5) { i -> (idx.indices).sumOf { jac[i][it] * r[it] } }
                var moved = false
                while (lambda < 1e8) {
                    val m = Array(5) { i -> DoubleArray(5) { j -> a[i][j] + if (i == j) lambda * (a[i][i] + 1e-9) else 0.0 } }
                    val step = solve5(m, DoubleArray(5) { -g[it] }) ?: return null
                    val q = DoubleArray(5) { p[it] + step[it] }
                    if (q[3] < 0) q[3] = 0.0
                    val rq = res(q)
                    val cq = rq.sumOf { it * it }
                    if (cq < cost) {
                        val small = step.all { abs(it) < 1e-6 }
                        p = q
                        r = rq
                        cost = cq
                        lambda = max(lambda * 0.3, 1e-9)
                        moved = !small
                        break
                    }
                    lambda *= 10
                }
                if (!moved) return of(p)
            }
            return of(p)
        }

        /** Gaussian elimination with pivoting; null when singular. */
        private fun solve5(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
                if (abs(m[piv][col]) < 1e-12) return null
                val t = m[col]; m[col] = m[piv]; m[piv] = t
                val tb = b[col]; b[col] = b[piv]; b[piv] = tb
                for (r in col + 1 until n) {
                    val f = m[r][col] / m[col][col]
                    for (k in col until n) m[r][k] -= f * m[col][k]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var acc = b[r]
                for (k in r + 1 until n) acc -= m[r][k] * x[k]
                x[r] = acc / m[r][r]
            }
            return x
        }
    }

    /** A stadium, not a street block or a circle a few metres across. */
    private fun sane() = radius in 8.0..70.0 && half in 0.0..120.0 && perimeter in 120.0..600.0

    /**
     * Most points on the loop (within a few metres), and the ones on it all round it: a part of a lap fits a far
     * bigger or smaller loop as well.
     */
    private fun covers(xs: DoubleArray, ys: DoubleArray, keep: BooleanArray): Boolean {
        val near = xs.indices.count { abs(off(xs[it], ys[it])) <= 4.0 }
        if (near < 0.8 * xs.size) return false
        val bins = BooleanArray(24)
        val sq = DoubleArray(xs.size)
        var n = 0
        for (i in xs.indices) {
            if (!keep[i]) continue
            bins[((arc(xs[i], ys[i]) / perimeter) * bins.size).toInt().coerceIn(0, bins.size - 1)] = true
            sq[n++] = off(xs[i], ys[i]).let { it * it }
        }
        val rms = sqrt(sq.take(n).average())
        return bins.count { it } >= bins.size - 1 && rms <= 2.5
    }
}
