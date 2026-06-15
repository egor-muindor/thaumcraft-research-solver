package io.github.muindor.tcresearchsolver.solver

data class Cost(val scarcity: Double, val cells: Double)

val ZERO_COST = Cost(0.0, 0.0)
val INF_COST = Cost(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)

fun compareCost(a: Cost, b: Cost): Int {
    if (a.scarcity != b.scarcity) return if (a.scarcity < b.scarcity) -1 else 1
    if (a.cells != b.cells) return if (a.cells < b.cells) -1 else 1
    return 0
}

fun lessThan(a: Cost, b: Cost): Boolean = compareCost(a, b) < 0

fun addCost(a: Cost, b: Cost): Cost = Cost(a.scarcity + b.scarcity, a.cells + b.cells)
