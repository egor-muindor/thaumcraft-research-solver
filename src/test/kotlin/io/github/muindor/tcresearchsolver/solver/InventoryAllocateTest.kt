package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Port of reference/ts-solver/tests/core/inventory.allocate.test.ts
 */
class InventoryAllocateTest {

    private val data = buildAspectData()

    @Test
    fun `takes directly when abundant - zero scarcity no crafts`() {
        val inv = makeInventory(listOf("air" to 100, "fire" to 100), DEFAULT_THRESHOLD)
        val r = allocate(inv, data, linkedMapOf("air" to 2, "fire" to 1))
        assertEquals(Feasible.TRUE, r.feasible)
        assertEquals(0.0, r.scarcityCost)
        assertEquals(0, r.craftOps)
        assertEquals(2, r.leafConsumption["air"])
        assertEquals(1, r.leafConsumption["fire"])
    }

    @Test
    fun `crafts when the aspect has zero direct supply but components are available`() {
        // need light=1 (light=air+fire), supply light=0
        val inv = makeInventory(listOf("air" to 100, "fire" to 100), DEFAULT_THRESHOLD)
        val r = allocate(inv, data, linkedMapOf("light" to 1))
        assertEquals(Feasible.TRUE, r.feasible)
        assertEquals(1, r.craftOps)
        assertEquals(1, r.leafConsumption["air"])
        assertEquals(1, r.leafConsumption["fire"])
        assertEquals(0, r.leafConsumption["light"] ?: 0)
    }

    @Test
    fun `reports infeasible when a needed primal cannot be supplied even via crafting`() {
        val inv = makeInventory(listOf("air" to 0, "fire" to 0), DEFAULT_THRESHOLD)
        val r = allocate(inv, data, linkedMapOf("light" to 1))
        assertEquals(Feasible.FALSE, r.feasible)
    }

    @Test
    fun `is order-independent and beats greedy under contention for a shared component`() {
        // Synthetic binary-BOM contention: acomp=air+fire, bcomp=air+water share air.
        // supply air=2 => both craftable.
        val synth = buildAspectData(BuildOptions(
            overrideCombinations = mapOf(
                "acomp" to ("air" to "fire"),
                "bcomp" to ("air" to "water"),
                "ccomp" to ("air" to "earth"),
            ),
            addons = emptyList(),
            overrideTranslate = mapOf("acomp" to "acomp", "bcomp" to "bcomp", "ccomp" to "ccomp"),
        ))
        val inv2 = makeInventory(listOf("air" to 2, "fire" to 100, "water" to 100), DEFAULT_THRESHOLD)
        val r = allocate(inv2, synth, linkedMapOf("acomp" to 1, "bcomp" to 1))
        assertEquals(Feasible.TRUE, r.feasible)
        assertEquals(2, r.leafConsumption["air"])

        // now air=1 => only one of the two compounds craftable, no direct supply => infeasible
        val inv1 = makeInventory(listOf("air" to 1, "fire" to 100, "water" to 100), DEFAULT_THRESHOLD)
        val r1 = allocate(inv1, synth, linkedMapOf("acomp" to 1, "bcomp" to 1))
        assertEquals(Feasible.FALSE, r1.feasible)
    }

    @Test
    fun `prefers the cheaper feasible mix - direct abundant over crafting`() {
        // light direct abundant => scarcityCost 0; craftOps may be 0 (direct preferred since cost equals)
        val inv = makeInventory(listOf("light" to 100, "air" to 100, "fire" to 100), DEFAULT_THRESHOLD)
        val r = allocate(inv, data, linkedMapOf("light" to 1))
        assertEquals(Feasible.TRUE, r.feasible)
        assertEquals(0.0, r.scarcityCost)
    }

    @Test
    fun `returns UNKNOWN when the node budget is exhausted (no false verdict)`() {
        val inv = makeInventory(listOf("air" to 100, "fire" to 100), DEFAULT_THRESHOLD)
        val r = allocate(inv, data, linkedMapOf("light" to 3), AllocBudget(maxNodes = 1))
        assertEquals(Feasible.UNKNOWN, r.feasible)
    }

    @Test
    fun `sum of obtainCost is a lower bound on the exact allocation scarcityCost`() {
        val inv = makeInventory(listOf("air" to 3, "fire" to 3), DEFAULT_THRESHOLD) // both scarce
        val demand = linkedMapOf("light" to 2)
        val r = allocate(inv, data, demand)
        // independent lower bound: 2 * obtainCost(light)
        val lb = 2 * obtainCost(inv, data, "light")
        assertTrue(lb <= r.scarcityCost)
    }

    @Test
    fun `mixes direct and craft under contention - takes one compound directly to free a shared component`() {
        // xx=air+fire, yy=air+water share air (supply 1). xx has direct supply (1), yy none.
        // Crafting BOTH needs 2 air (only 1) => must take xx directly and craft yy from the single air.
        val synth = buildAspectData(BuildOptions(
            overrideCombinations = mapOf(
                "xx" to ("air" to "fire"),
                "yy" to ("air" to "water"),
            ),
            addons = emptyList(),
            overrideTranslate = mapOf("xx" to "xx", "yy" to "yy"),
        ))
        val inv = makeInventory(listOf("xx" to 1, "air" to 1, "fire" to 100, "water" to 100), DEFAULT_THRESHOLD)
        val r = allocate(inv, synth, linkedMapOf("xx" to 1, "yy" to 1))
        assertEquals(Feasible.TRUE, r.feasible)
        assertEquals(1, r.leafConsumption["xx"])
        assertEquals(1, r.leafConsumption["air"])
        assertEquals(1, r.craftOps)
    }
}
