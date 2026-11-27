package net.prototux.aare.ui.car.navi

import org.maplibre.spatialk.geojson.Position

/**
 * Where the stops on the way lie along the guided route (O206, 16.11.2026: "ankunft restzeit restweg zum zwischenziel
 * wird nicht angezeigt. wäre aber cool wenn er beides anzeigen könnte ohne zu klobig zu werden"): metres and seconds from
 * the start to each stop before the destination, so the tile can say what is left to the next one.
 */
internal object NaviStops {
    class Mark(val at: Position, val metres: Double, val seconds: Double)

    /**
     * [points] the route's line, [steps] each step's metres and seconds in order, [stops] the waypoints before the
     * destination in order. Each stop is placed at the nearest point after the previous one; its time follows the steps'
     * own pace.
     */
    fun marks(points: List<Position>, steps: List<Pair<Double, Double>>, stops: List<Position>): List<Mark> {
        if (points.size < 2 || stops.isEmpty()) return emptyList()
        val m = DoubleArray(points.size)
        for (i in 1 until points.size) m[i] = m[i - 1] + ChargerIndex.distanceM(points[i - 1], points[i])
        var from = 0
        return stops.map { s ->
            val i = (from until points.size).minByOrNull { ChargerIndex.distanceM(points[it], s) } ?: from
            from = i
            Mark(s, m[i], secondsAt(m[i], steps))
        }
    }

    /** Seconds from the start to [metres] along the route, at the pace of the step it lies in. */
    fun secondsAt(metres: Double, steps: List<Pair<Double, Double>>): Double {
        var d = 0.0; var t = 0.0
        for ((len, sec) in steps) {
            if (metres <= d + len) return t + if (len > 0) sec * (metres - d) / len else 0.0
            d += len; t += sec
        }
        return t
    }

    /** What is left to the next stop ahead, given how far and how long the car has come; null when none is ahead. */
    class Ahead(val mark: Mark, val metresLeft: Double, val secondsLeft: Double)

    fun next(marks: List<Mark>, drivenM: Double, drivenS: Double): Ahead? =
        marks.firstOrNull { it.metres > drivenM + PASSED_M }?.let { Ahead(it, it.metres - drivenM, (it.seconds - drivenS).coerceAtLeast(0.0)) }

    /**
     * The waypoints in the order they lie along [line] (O222: the charging stops were put after the driver's own stops,
     * and Valhalla visits them in the order given): each at the point of the line nearest to it.
     */
    fun inOrder(line: List<Position>, points: List<Position>): List<Position> {
        if (line.size < 2 || points.size < 2) return points
        val cum = DoubleArray(line.size); for (i in 1 until line.size) cum[i] = cum[i - 1] + ChargerIndex.distanceM(line[i - 1], line[i])
        return points.sortedBy { p -> cum[line.indices.minByOrNull { ChargerIndex.distanceM(line[it], p) } ?: 0] }
    }

    /**
     * Metres from [p] to the nearest point of [line], between its points too (a motorway's line has points far apart on a
     * straight; Navi I-075: whether the route goes to a planned charger at all).
     */
    fun offLineM(line: List<Position>, p: Position): Double {
        if (line.isEmpty()) return Double.MAX_VALUE
        if (line.size == 1) return ChargerIndex.distanceM(line[0], p)
        val kx = 111_320.0 * Math.cos(Math.toRadians(p.latitude)); val ky = 111_320.0
        var best = Double.MAX_VALUE
        for (i in 1 until line.size) {
            val ax = (line[i - 1].longitude - p.longitude) * kx; val ay = (line[i - 1].latitude - p.latitude) * ky
            val bx = (line[i].longitude - p.longitude) * kx; val by = (line[i].latitude - p.latitude) * ky
            val dx = bx - ax; val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len <= 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            val x = ax + t * dx; val y = ay + t * dy
            best = minOf(best, Math.sqrt(x * x + y * y))
        }
        return best
    }

    /** A stop this close behind or ahead counts as reached. */
    private const val PASSED_M = 30.0
}
