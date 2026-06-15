package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Port of reference/ts-solver/tests/core/inventory.cost.test.ts
 *
 * Note on "rejects non-integer supply": in Kotlin, Inventory.supply is Map<Aspect, Int>,
 * so passing a non-integer value is a compile-time type error. That case is enforced by the
 * type system rather than a runtime check and is therefore omitted here.
 */
class InventoryCostTest {

    private val data = buildAspectData()

    // ── validateInventory ────────────────────────────────────────────────────

    @Test
    fun `accepts non-negative integer supply and threshold gt 0`() {
        assertDoesNotThrow { validateInventory(makeInventory(listOf("air" to 10), 50)) }
    }

    @Test
    fun `rejects negative supply`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateInventory(makeInventory(listOf("air" to -1), 50))
        }
    }

    @Test
    fun `rejects threshold le 0`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateInventory(makeInventory(emptyList(), 0))
        }
    }

    // ── directPenalty ────────────────────────────────────────────────────────

    private val invDirect = makeInventory(listOf("air" to 50, "fire" to 10, "water" to 0), DEFAULT_THRESHOLD)

    @Test
    fun `directPenalty is 0 for abundant (supply ge threshold)`() {
        assertEquals(0.0, directPenalty(invDirect, "air"))
    }

    @Test
    fun `directPenalty is base plus k times (threshold minus supply) for scarce`() {
        assertEquals(BASE + K * (DEFAULT_THRESHOLD - 10), directPenalty(invDirect, "fire"))
    }

    @Test
    fun `directPenalty is +Infinity for zero supply (must craft)`() {
        assertEquals(Double.POSITIVE_INFINITY, directPenalty(invDirect, "water"))
    }

    // ── obtainCost ───────────────────────────────────────────────────────────

    @Test
    fun `obtainCost equals directPenalty for a primal`() {
        val inv = makeInventory(listOf("air" to 10), DEFAULT_THRESHOLD)
        assertEquals(directPenalty(inv, "air"), obtainCost(inv, data, "air"))
    }

    @Test
    fun `lets an abundant component rescue a zero-supply compound via crafting`() {
        // void = air + entropy. supply[void]=0 (direct +Inf) but air & entropy abundant => craft cost finite.
        val inv = makeInventory(listOf("air" to 100, "entropy" to 100), DEFAULT_THRESHOLD)
        assertEquals(Double.POSITIVE_INFINITY, directPenalty(inv, "void"))
        assertEquals(0.0, obtainCost(inv, data, "void")) // 0 + 0 from abundant primals
    }

    @Test
    fun `prefers direct when cheaper than crafting`() {
        // void abundant directly => obtainCost 0 even if components scarce
        val inv = makeInventory(listOf("void" to 100, "air" to 1, "entropy" to 1), DEFAULT_THRESHOLD)
        assertEquals(0.0, obtainCost(inv, data, "void"))
    }

    @Test
    fun `is monotone - more supply never increases obtainCost`() {
        val lean = makeInventory(listOf("air" to 5, "entropy" to 5), DEFAULT_THRESHOLD)
        val rich = makeInventory(listOf("air" to 80, "entropy" to 80), DEFAULT_THRESHOLD)
        assertTrue(obtainCost(rich, data, "void") <= obtainCost(lean, data, "void"))
    }

    // ── globalMinObtain ──────────────────────────────────────────────────────

    @Test
    fun `globalMinObtain is 0 when any aspect is abundant`() {
        val inv = makeInventory(listOf("air" to 100), DEFAULT_THRESHOLD)
        assertEquals(0.0, globalMinObtain(inv, data))
    }

    // ── cache soundness ──────────────────────────────────────────────────────

    @Test
    fun `obtainCost uses separate cache per AspectData - no stale cost across data on one Inventory`() {
        // Each call uses a fresh HashMap cache (G7: no WeakMap needed — callers pass their own cache).
        val inv = makeInventory(listOf("air" to 0, "fire" to 100, "water" to 100), DEFAULT_THRESHOLD)
        val dataA = buildAspectData(BuildOptions(
            overrideCombinations = mapOf("compound" to ("fire" to "water")),
            addons = emptyList(),
            overrideTranslate = mapOf("compound" to "compound"),
        ))
        val dataB = buildAspectData(BuildOptions(
            overrideCombinations = mapOf("compound" to ("air" to "fire")),
            addons = emptyList(),
            overrideTranslate = mapOf("compound" to "compound"),
        ))
        // Each call gets its own fresh cache so there is no cross-data contamination.
        assertEquals(0.0, obtainCost(inv, dataA, "compound", HashMap()))
        assertEquals(Double.POSITIVE_INFINITY, obtainCost(inv, dataB, "compound", HashMap()))
    }
}
