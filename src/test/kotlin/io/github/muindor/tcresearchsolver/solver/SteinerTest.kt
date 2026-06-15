package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SteinerTest {

    // Build a small explicit graph: a path 0-1-2-3; weights all termWeights at ends, freeWeight inside.
    private fun pathGraph(n: Int, termWeights: Double, freeWeight: Double): SteinerGraph {
        val adj: Array<List<Int>> = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n - 1) {
            (adj[i] as MutableList).add(i + 1)
            (adj[i + 1] as MutableList).add(i)
        }
        return object : SteinerGraph {
            override val size: Int = n
            override fun neighbors(v: Int): List<Int> = adj[v]
            override fun weight(v: Int): Double = if (v == 0 || v == n - 1) termWeights else freeWeight
            override val terminals: List<Int> = listOf(0, n - 1)
        }
    }

    @Test
    fun `single terminal returns its own weight`() {
        val g = object : SteinerGraph {
            override val size: Int = 1
            override fun neighbors(v: Int): List<Int> = emptyList()
            override fun weight(v: Int): Double = 0.0
            override val terminals: List<Int> = listOf(0)
        }
        assertEquals(0.0, steinerNodeWeighted(g))
    }

    @Test
    fun `path of 4 - 2 terminals weight 0 plus 2 inner free weight 1 equals 2`() {
        assertEquals(2.0, steinerNodeWeighted(pathGraph(4, 0.0, 1.0)))
    }

    @Test
    fun `counts terminal weights too`() {
        // terminals weight 5 each, 2 inner weight 1 => 12
        assertEquals(12.0, steinerNodeWeighted(pathGraph(4, 5.0, 1.0)))
    }

    @Test
    fun `picks the cheaper of two parallel routes (star, not MST overcount)`() {
        // center 0 connects to t1=1, t2=2, t3=3 (all terminals). Optimal Steiner = center+3 terminals.
        // weights: center 1, terminals 0 => total 1 (NOT 2 like a pairwise-MST overestimate).
        val adjList = arrayOf(listOf(1, 2, 3), listOf(0), listOf(0), listOf(0))
        val g = object : SteinerGraph {
            override val size: Int = 4
            override fun neighbors(v: Int): List<Int> = adjList[v]
            override fun weight(v: Int): Double = if (v == 0) 1.0 else 0.0
            override val terminals: List<Int> = listOf(1, 2, 3)
        }
        assertEquals(1.0, steinerNodeWeighted(g))
    }

    @Test
    fun `returns Infinity when terminals are disconnected`() {
        val adjList = arrayOf<List<Int>>(emptyList(), emptyList())
        val g = object : SteinerGraph {
            override val size: Int = 2
            override fun neighbors(v: Int): List<Int> = adjList[v]
            override fun weight(v: Int): Double = 0.0
            override val terminals: List<Int> = listOf(0, 1)
        }
        assertEquals(Double.POSITIVE_INFINITY, steinerNodeWeighted(g))
    }

    @Test
    fun `throws a clear error when terminal count exceeds the limit`() {
        val n = 31
        val adjList: Array<List<Int>> = Array(n) { emptyList() }
        val g = object : SteinerGraph {
            override val size: Int = n
            override fun neighbors(v: Int): List<Int> = adjList[v]
            override fun weight(v: Int): Double = 0.0
            override val terminals: List<Int> = (0 until 31).toList()
        }
        val ex = assertThrows(IllegalArgumentException::class.java) { steinerNodeWeighted(g) }
        assertTrue(ex.message!!.contains("too many terminals"), "message should mention 'too many terminals', got: ${ex.message}")
    }
}
