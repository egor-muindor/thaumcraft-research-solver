package io.github.muindor.tcresearchsolver.integration

import io.github.muindor.tcresearchsolver.solver.AspectDataError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [buildAspectDataFrom] using a small fake registry.
 *
 * IMPORTANT: This test NEVER calls [AspectDataProvider.fromLiveRegistry] or
 * [liveRegistryEntries] — those touch thaumcraft.api.aspects.Aspect which is
 * compile-time only in unit tests (no live registry in a JVM unit test).
 */
class AspectDataProviderTest {

    // Fake registry: air (primal), entropy (primal), fire (primal),
    //   void = air + entropy, flux = entropy + fire
    private val fakeEntries = listOf(
        RegistryEntry("air", emptyList(), isPrimal = true),
        RegistryEntry("entropy", emptyList(), isPrimal = true),
        RegistryEntry("fire", emptyList(), isPrimal = true),
        RegistryEntry("void", listOf("air", "entropy"), isPrimal = false),
        RegistryEntry("flux", listOf("entropy", "fire"), isPrimal = false),
    )

    // ----- primals -----

    @Test fun `primals contains exactly the three primal entries in registry order`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertEquals(linkedSetOf("air", "entropy", "fire"), data.primals)
        assertEquals(listOf("air", "entropy", "fire"), data.primals.toList())
    }

    @Test fun `primals does not contain any compound`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertFalse(data.primals.contains("void"))
        assertFalse(data.primals.contains("flux"))
    }

    // ----- combinations -----

    @Test fun `combinations maps void to (air, entropy)`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertEquals(Pair("air", "entropy"), data.combinations["void"])
    }

    @Test fun `combinations maps flux to (entropy, fire)`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertEquals(Pair("entropy", "fire"), data.combinations["flux"])
    }

    @Test fun `combinations does not contain primals`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertFalse(data.combinations.containsKey("air"))
        assertFalse(data.combinations.containsKey("entropy"))
        assertFalse(data.combinations.containsKey("fire"))
    }

    // ----- universe -----

    @Test fun `universe contains all 5 aspects`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertEquals(5, data.universe.size)
        assertTrue(data.universe.containsAll(listOf("air", "entropy", "fire", "void", "flux")))
    }

    @Test fun `universe lists primals before compounds (primals-first invariant)`() {
        val data = buildAspectDataFrom(fakeEntries)
        val list = data.universe.toList()
        // All primals must appear before any compound
        val lastPrimalIdx = maxOf(list.indexOf("air"), list.indexOf("entropy"), list.indexOf("fire"))
        val firstCompoundIdx = minOf(list.indexOf("void"), list.indexOf("flux"))
        assertTrue(lastPrimalIdx < firstCompoundIdx,
            "Expected all primals before compounds but got order: $list")
    }

    // ----- adjacency -----

    @Test fun `adjacency is symmetric (a in adj(b) iff b in adj(a))`() {
        val data = buildAspectDataFrom(fakeEntries)
        for ((a, nbrs) in data.adjacency) {
            for (b in nbrs) {
                assertTrue(data.adjacency[b]?.contains(a) == true,
                    "adjacency not symmetric: $a in adj($b) but $b not in adj($a)")
            }
        }
    }

    @Test fun `adjacency connects void to air (compound to component)`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertTrue(data.adjacency["void"]?.contains("air") == true)
        assertTrue(data.adjacency["air"]?.contains("void") == true)
    }

    @Test fun `adjacency connects void to entropy`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertTrue(data.adjacency["void"]?.contains("entropy") == true)
        assertTrue(data.adjacency["entropy"]?.contains("void") == true)
    }

    @Test fun `adjacency connects flux to entropy`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertTrue(data.adjacency["flux"]?.contains("entropy") == true)
        assertTrue(data.adjacency["entropy"]?.contains("flux") == true)
    }

    @Test fun `adjacency connects flux to fire`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertTrue(data.adjacency["flux"]?.contains("fire") == true)
        assertTrue(data.adjacency["fire"]?.contains("flux") == true)
    }

    @Test fun `adjacency does not connect siblings (air and fire share no edge)`() {
        val data = buildAspectDataFrom(fakeEntries)
        // air and fire are both components of different compounds but are not linked to each other
        assertFalse(data.adjacency["air"]?.contains("fire") == true)
        assertFalse(data.adjacency["fire"]?.contains("air") == true)
    }

    @Test fun `adjacency does not link primals to each other`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertFalse(data.adjacency["air"]?.contains("entropy") == true)
        assertFalse(data.adjacency["air"]?.contains("fire") == true)
        assertFalse(data.adjacency["entropy"]?.contains("fire") == true)
    }

    @Test fun `adjacency entry exists for every universe member`() {
        val data = buildAspectDataFrom(fakeEntries)
        for (a in data.universe) {
            assertNotNull(data.adjacency[a], "No adjacency entry for $a")
        }
    }

    // ----- translate (identity mapping for live path) -----

    @Test fun `translate contains every universe member`() {
        val data = buildAspectDataFrom(fakeEntries)
        for (a in data.universe) {
            assertTrue(data.translate.containsKey(a), "translate missing key '$a'")
        }
    }

    @Test fun `translate is identity (tag maps to itself) for all aspects`() {
        val data = buildAspectDataFrom(fakeEntries)
        for (a in data.universe) {
            assertEquals(a, data.translate[a], "translate[$a] should be identity")
        }
    }

    // ----- order -----

    @Test fun `order covers the whole universe exactly once`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertEquals(data.universe.size, data.order.size)
        assertEquals(data.universe.toSet(), data.order.toSet())
    }

    @Test fun `order lists primals first in registry order`() {
        val data = buildAspectDataFrom(fakeEntries)
        assertEquals(listOf("air", "entropy", "fire"), data.order.take(3))
    }

    @Test fun `order lists compounds after primals in registry order`() {
        val data = buildAspectDataFrom(fakeEntries)
        // compounds: void then flux (registry order)
        val compoundOrder = data.order.drop(3)
        assertEquals(listOf("void", "flux"), compoundOrder)
    }

    // ----- validation (mirrors buildAspectData) -----

    @Test fun `throws AspectDataError on self-referential compound`() {
        val bad = listOf(
            RegistryEntry("air", emptyList(), isPrimal = true),
            RegistryEntry("loop", listOf("loop", "air"), isPrimal = false),
        )
        val ex = assertThrows(AspectDataError::class.java) {
            buildAspectDataFrom(bad)
        }
        assertTrue(ex.message?.contains("loop") == true)
    }

    @Test fun `throws AspectDataError when component is undefined`() {
        val bad = listOf(
            RegistryEntry("air", emptyList(), isPrimal = true),
            RegistryEntry("mystery", listOf("air", "doesnotexist"), isPrimal = false),
        )
        val ex = assertThrows(AspectDataError::class.java) {
            buildAspectDataFrom(bad)
        }
        assertTrue(ex.message?.contains("doesnotexist") == true)
    }

    @Test fun `throws AspectDataError on a cycle`() {
        val bad = listOf(
            RegistryEntry("air", emptyList(), isPrimal = true),
            RegistryEntry("acomp", listOf("bcomp", "air"), isPrimal = false),
            RegistryEntry("bcomp", listOf("acomp", "air"), isPrimal = false),
        )
        assertThrows(AspectDataError::class.java) {
            buildAspectDataFrom(bad)
        }
    }
}
