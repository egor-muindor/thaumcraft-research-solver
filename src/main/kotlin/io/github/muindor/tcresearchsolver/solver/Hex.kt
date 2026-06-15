package io.github.muindor.tcresearchsolver.solver

data class Hex(val q: Int, val r: Int)

val HEX_DIRECTIONS: List<Hex> = listOf(
    Hex(1, 0), Hex(1, -1), Hex(0, -1),
    Hex(-1, 0), Hex(-1, 1), Hex(0, 1),
)

fun hexKey(h: Hex): String = "${h.q},${h.r}"

private val HEX_KEY_RE = Regex("^-?\\d+$")

fun parseHexKey(key: String): Hex {
    val i = key.indexOf(',')
    if (i < 0 || i != key.lastIndexOf(',')) throw IllegalArgumentException("bad hex key: $key")
    val qs = key.substring(0, i)
    val rs = key.substring(i + 1)
    if (!HEX_KEY_RE.matches(qs) || !HEX_KEY_RE.matches(rs)) throw IllegalArgumentException("bad hex key: $key")
    return Hex(qs.toInt(), rs.toInt())
}

fun neighborsOf(h: Hex): List<Hex> = HEX_DIRECTIONS.map { Hex(h.q + it.q, h.r + it.r) }

fun distance(a: Hex, b: Hex): Int {
    val dq = a.q.toLong() - b.q.toLong()
    val dr = a.r.toLong() - b.r.toLong()
    return ((kotlin.math.abs(dq) + kotlin.math.abs(dr) + kotlin.math.abs(dq + dr)) / 2).toInt()
}

fun isOnBoard(h: Hex, radius: Int): Boolean = distance(Hex(0, 0), h) <= radius

fun boardCells(radius: Int): List<Hex> {
    val cells = ArrayList<Hex>()
    for (q in -radius..radius) {
        val rLo = maxOf(-radius, -q - radius)
        val rHi = minOf(radius, -q + radius)
        for (r in rLo..rHi) cells.add(Hex(q, r))
    }
    return cells
}
