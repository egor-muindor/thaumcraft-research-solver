package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AspectGraphTest {

    private val data = buildAspectData()

    // --- isValidLink ---

    @Test fun `isValidLink true for directly-combined aspects`() {
        assertTrue(isValidLink(data, "magic", "void"))
        assertTrue(isValidLink(data, "void", "magic"))
    }

    @Test fun `isValidLink false for identical aspects (no self-link)`() {
        assertFalse(isValidLink(data, "air", "air"))
    }

    @Test fun `isValidLink false for siblings and unrelated aspects`() {
        assertFalse(isValidLink(data, "light", "energy"))
        assertFalse(isValidLink(data, "air", "earth"))
    }

    // --- neighbors ---

    @Test fun `neighbors returns the adjacency set`() {
        assertTrue(neighbors(data, "magic").contains("energy"))
    }

    // --- primalVec ---

    @Test fun `primalVec maps a primal to itself with count 1`() {
        val v = primalVec(data, "air")
        assertEquals(1, v.size)
        assertEquals(1, v["air"])
    }

    @Test fun `primalVec decomposes a compound into a primal multiset`() {
        // void = air + entropy
        val v = primalVec(data, "void")
        assertEquals(1, v["air"])
        assertEquals(1, v["entropy"])
        // magic = void + energy = (air+entropy) + (order+fire)
        val m = primalVec(data, "magic")
        assertEquals(1, m["air"])
        assertEquals(1, m["entropy"])
        assertEquals(1, m["order"])
        assertEquals(1, m["fire"])
    }

    @Test fun `primalVec only contains primals as keys`() {
        val vec: Map<Aspect, Int> = primalVec(data, "electricity")
        for (k in vec.keys) {
            assertTrue(data.primals.contains(k), "expected '$k' to be a primal")
        }
    }

    // --- encapsulation (defensive copies) ---

    @Test fun `neighbors result cannot corrupt the backing graph`() {
        val d = buildAspectData()
        @Suppress("UNCHECKED_CAST")
        val ns = neighbors(d, "air") as MutableSet<String>
        ns.add("earth")
        assertFalse(isValidLink(d, "air", "earth"))
        assertFalse(neighbors(d, "air").contains("earth"))
    }

    @Test fun `primalVec result cannot poison the memo cache`() {
        val d = buildAspectData()
        @Suppress("UNCHECKED_CAST")
        val v = primalVec(d, "void") as MutableMap<String, Int>
        v["air"] = 999
        assertEquals(1, primalVec(d, "void")["air"])
    }

    // --- mult ---

    @Test fun `mult is 0 for a primal target`() {
        assertEquals(0, mult(data, "air", "air"))
    }

    @Test fun `mult is 1 for each distinct component`() {
        assertEquals(1, mult(data, "void", "magic"))
        assertEquals(1, mult(data, "energy", "magic"))
        assertEquals(0, mult(data, "air", "magic"))
    }

    @Test fun `mult counts repeats (synthetic dbl = air + air gives 2)`() {
        val d2 = buildAspectData(BuildOptions(
            overrideCombinations = mapOf("dbl" to Pair("air", "air")),
            addons = emptyList(),
            overrideTranslate = mapOf("dbl" to "dbl"),
        ))
        assertEquals(2, mult(d2, "air", "dbl"))
    }
}
