package forma.core.run

/**
 * Kilometre splits of a run from its GPS track: the time of each whole kilometre, in order. Fixes worse than 25 m and
 * jumps faster than any runner (a fix thrown off) are left out of the way.
 */
object Splits {
    data class Point(val timeMs: Long, val lat: Double, val lon: Double, val accuracy: Float)

    fun byKm(points: List<Point>): List<Double> {
        val ok = points.filter { it.accuracy <= 25f }.sortedBy { it.timeMs }
        if (ok.size < 2) return emptyList()
        val out = mutableListOf<Double>()
        var dist = 0.0
        var lastKmAt = ok.first().timeMs.toDouble()
        var prev = ok.first()
        for (p in ok.drop(1)) {
            val dt = (p.timeMs - prev.timeMs) / 1000.0
            if (dt <= 0) continue
            val d = Geo.haversine(prev.lat, prev.lon, p.lat, p.lon)
            if (d / dt > 12.0) continue
            // The moment each kilometre was passed, between the two fixes.
            val end = dist + d
            while (end >= (out.size + 1) * 1000.0) {
                val mark = (out.size + 1) * 1000.0
                val at = prev.timeMs + (p.timeMs - prev.timeMs) * ((mark - dist) / d)
                out += (at - lastKmAt) / 1000.0
                lastKmAt = at
            }
            dist = end
            prev = p
        }
        return out
    }
}
