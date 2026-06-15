package io.github.muindor.tcresearchsolver.integration

import io.github.muindor.tcresearchsolver.solver.DEFAULT_THRESHOLD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [toInventory] — the pure mapper from an amounts map to a solver [Inventory].
 *
 * IMPORTANT: This test NEVER calls [InventoryReader.read] — that method touches
 * RT's [elan.tweaks.thaumcraft.research.frontend.domain.ports.required.AspectPool]
 * and TC's [thaumcraft.api.aspects.Aspect], which are not available in a unit-test JVM.
 * Only the pure [toInventory] function is exercised here.
 */
class InventoryReaderTest {

    // ----- basic supply passthrough -----

    @Test fun `supply map is passed through as-is for all entries`() {
        val amounts = mapOf("ignis" to 60, "aqua" to 10, "terra" to 0)
        val inv = toInventory(amounts, threshold = 50)

        assertEquals(60, inv.supply["ignis"])
        assertEquals(10, inv.supply["aqua"])
        assertEquals(0,  inv.supply["terra"])
    }

    // ----- threshold passthrough -----

    @Test fun `threshold is passed through unchanged`() {
        val amounts = mapOf("ignis" to 30)
        val inv = toInventory(amounts, threshold = 75)

        assertEquals(75, inv.threshold)
    }

    @Test fun `threshold uses DEFAULT_THRESHOLD when not overridden`() {
        // toInventory always requires an explicit threshold; verify DEFAULT_THRESHOLD value
        val amounts = mapOf("aer" to 5)
        val inv = toInventory(amounts, threshold = DEFAULT_THRESHOLD)

        assertEquals(DEFAULT_THRESHOLD, inv.threshold)
    }

    // ----- absent aspect → null in supply (solver treats missing as 0) -----

    @Test fun `aspect absent from amounts map is null in supply`() {
        val amounts = mapOf("ignis" to 60)
        val inv = toInventory(amounts, threshold = 50)

        // "terra" was not in amounts; the solver will do supply["terra"] ?: 0
        assertNull(inv.supply["terra"])
    }

    // ----- empty map -----

    @Test fun `empty amounts map produces empty supply`() {
        val inv = toInventory(emptyMap<String, Int>(), threshold = 50)
        assertTrue(inv.supply.isEmpty())
    }

    // ----- insertion order preservation -----

    @Test fun `supply preserves insertion order of input amounts map`() {
        // LinkedHashMap input → LinkedHashMap output must keep the same iteration order.
        val amounts = linkedMapOf("terra" to 5, "ignis" to 60, "aqua" to 10)
        val inv = toInventory(amounts, threshold = 50)

        val keys = inv.supply.keys.toList()
        assertEquals(listOf("terra", "ignis", "aqua"), keys)
    }
}
