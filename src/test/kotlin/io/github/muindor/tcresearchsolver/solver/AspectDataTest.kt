package io.github.muindor.tcresearchsolver.solver

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

// Canonical undirected edge set (each edge endpoints sorted, list sorted). Extracted directly
// from the GTNH 2.8.4 mod jars by scripts/extract-aspects.mjs; frozen as the normative fixture
// (spec §2.1). Covers base Thaumcraft 4.2.3.5a + fm/mb/gt/tb/av addon aspects.
private val EXPECTED_EDGES = listOf(
    "aequalitas--mind","aequalitas--order","air--aura","air--flight","air--light","air--motion","air--senses","air--tree",
    "air--void","air--weather","armor--earth","armor--tabernus","armor--tool","astrum--light","astrum--primordium","aura--magic",
    "beast--cloth","beast--flesh","beast--life","beast--man","beast--motion","caelum--crystal","caelum--metal","cheatiness--greed",
    "cheatiness--mine","cloth--tool","cold--entropy","cold--fire","craft--man","craft--tool","crop--harvest","crop--man",
    "crop--plant","crystal--earth","crystal--metal","crystal--order","darkness--eldritch","darkness--light","darkness--void","death--entropy",
    "death--flesh","death--life","death--soul","death--undead","earth--life","earth--metal","earth--mine","earth--plant",
    "earth--travel","eldritch--terminus","eldritch--void","electricity--energy","electricity--mechanism","energy--fire","energy--magic","energy--order",
    "energy--radioactivity","entropy--exchange","entropy--poison","entropy--stupidity","entropy--taint","entropy--trap","entropy--void","envy--hunger",
    "envy--senses","exchange--order","fire--light","fire--mind","fire--nether","fire--weapon","fire--wrath","flesh--lust",
    "flight--motion","flight--pride","gloria--man","gloria--travel","gluttony--hunger","gluttony--void","greed--hunger","greed--man",
    "greed--terminus","harvest--tool","heal--life","heal--order","hunger--life","hunger--lust","hunger--void","life--plant",
    "life--slime","life--soul","life--water","light--radioactivity","magic--nether","magic--taint","magic--void","magnetism--metal",
    "magnetism--travel","man--mind","man--mine","man--tool","mechanism--motion","mechanism--tool","mind--soul","mind--stupidity",
    "mind--vesania","motion--order","motion--primordium","motion--trap","motion--travel","motion--undead","order--time","order--tool",
    "plant--tree","poison--water","pride--void","primordium--void","senses--soul","slime--water","sloth--soul","sloth--trap",
    "tabernus--travel","taint--vesania","time--void","tool--weapon","water--weather","weapon--wrath",
)

class AspectDataTest {

    // --- buildAspectData (defaults: TC 4.2.3.5a + fm/mb/gt/tb/av) ---

    @Test fun `has 69 aspects in the universe`() {
        val data = buildAspectData()
        assertEquals(69, data.universe.size)
    }

    @Test fun `has exactly 6 primals with no combinations`() {
        val data = buildAspectData()
        assertEquals(6, data.primals.size)
        for (p in data.primals) assertFalse(data.combinations.containsKey(p))
    }

    @Test fun `builds exactly the canonical normative edge set (126 undirected edges)`() {
        val data = buildAspectData()
        val edges = mutableSetOf<String>()
        for ((a, nbrs) in data.adjacency) {
            for (b in nbrs) {
                val sorted = listOf(a, b).sorted()
                edges.add("${sorted[0]}--${sorted[1]}")
            }
        }
        val sorted = edges.sorted()
        assertEquals(EXPECTED_EDGES, sorted)
        assertEquals(126, sorted.size)
    }

    @Test fun `connects compound to each direct component (undirected)`() {
        val data = buildAspectData()
        // magic = void + energy
        assertTrue(data.adjacency["magic"]!!.contains("void"))
        assertTrue(data.adjacency["void"]!!.contains("magic"))
        assertTrue(data.adjacency["magic"]!!.contains("energy"))
        // addon: electricity = energy + mechanism
        assertTrue(data.adjacency["electricity"]!!.contains("energy"))
        assertTrue(data.adjacency["mechanism"]!!.contains("electricity"))
    }

    @Test fun `does NOT connect siblings (shared parent is not an edge)`() {
        val data = buildAspectData()
        // light = air+fire, energy = order+fire: both children of fire, but not linked to each other
        assertFalse(data.adjacency["light"]?.contains("energy") ?: false)
        // primals are not linked to each other
        assertFalse(data.adjacency["air"]?.contains("earth") ?: false)
    }

    @Test fun `includes every recipe component in the universe`() {
        val data = buildAspectData()
        for ((_, components) in data.combinations) {
            assertTrue(data.universe.contains(components.first))
            assertTrue(data.universe.contains(components.second))
        }
    }

    @Test fun `has a latin translation for every aspect`() {
        val data = buildAspectData()
        for (a in data.universe) {
            val latin = iconLatin(data, a)
            assertTrue(latin.isNotEmpty())
        }
    }

    @Test fun `includes a declared addon-style aspect that has no recipe of its own (spec 2-1 union)`() {
        val d = buildAspectData(BuildOptions(
            overrideCombinations = mapOf("foo" to Pair("air", "fire")),
            overrideDeclaredAspects = listOf("standalone"),
            overrideTranslate = mapOf("foo" to "foo", "standalone" to "standalone"),
            addons = emptyList(),
        ))
        assertTrue(d.universe.contains("standalone"))
    }

    // --- GTNH 2.8.4 aspect additions (extracted from mods) ---

    @Test fun `registers every new aspect with its mod-defined components and icon`() {
        val data = buildAspectData()
        // GregTech custom aspects + Thaumic Boots + Avaritia
        val newAspects = mapOf(
            "gloria" to listOf("man", "travel"),
            "aequalitas" to listOf("mind", "order"),
            "vesania" to listOf("mind", "taint"),
            "primordium" to listOf("motion", "void"),
            "astrum" to listOf("light", "primordium"),
            "tabernus" to listOf("armor", "travel"),
            "caelum" to listOf("crystal", "metal"),
            "terminus" to listOf("eldritch", "greed"),
        )
        for ((key, comps) in newAspects) {
            assertTrue(data.universe.contains(key), "universe should contain $key")
            val recipe = data.combinations[key]!!
            assertEquals(comps, listOf(recipe.first, recipe.second).sorted())
            assertEquals(key, iconLatin(data, key)) // new aspects keyed by their latin tag
        }
    }

    @Test fun `fixes the strontio icon (was the stronito typo in the ythri port)`() {
        val data = buildAspectData()
        assertEquals("strontio", iconLatin(data, "stupidity"))
    }

    @Test fun `drops the stale stone-seed aspects absent from TC 4-2-3-5a`() {
        val data = buildAspectData()
        assertFalse(data.universe.contains("stone"))
        assertFalse(data.universe.contains("seed"))
        assertFalse(data.translate.containsKey("stone"))
        assertFalse(data.translate.containsKey("seed"))
    }

    @Test fun `keeps astrum to primordium (a compound built on another addon aspect)`() {
        val data = buildAspectData()
        assertTrue(data.adjacency["astrum"]!!.contains("primordium"))
        assertTrue(data.adjacency["primordium"]!!.contains("astrum"))
    }

    // --- aspect display order (Thaumcraft registration order, not alphabetical) ---

    @Test fun `order covers the whole universe exactly once`() {
        val data = buildAspectData()
        assertEquals(data.universe.size, data.order.size)
        assertEquals(data.universe.toSet(), data.order.toSet())
    }

    @Test fun `lists the 6 primals first, in Thaumcraft declaration order`() {
        val data = buildAspectData()
        assertEquals(listOf("air", "earth", "fire", "water", "order", "entropy"), data.order.take(6))
    }

    @Test fun `orders by mod tier, not alphabetically`() {
        val data = buildAspectData()
        val idx = { a: String -> data.order.indexOf(a) }
        // base compound (void) registered before any GregTech addon aspect
        assertTrue(idx("void") < idx("gloria"))
        assertTrue(idx("terminus") > idx("void"))
        // 'void' precedes 'aura' here — something alphabetical order would never do
        assertTrue(idx("void") < idx("aura"))
    }

    // --- startup validation (fail loudly) ---

    @Test fun `throws AspectDataError naming the aspect on a self-referential recipe`() {
        val ex = assertThrows(AspectDataError::class.java) {
            buildAspectData(BuildOptions(
                overrideCombinations = mapOf("foo" to Pair("foo", "air")),
                addons = emptyList(),
            ))
        }
        assertTrue(ex.message?.contains("foo") == true)
    }

    @Test fun `throws on a cycle (a-b-a bidirectional)`() {
        assertThrows(AspectDataError::class.java) {
            buildAspectData(BuildOptions(
                overrideCombinations = mapOf(
                    "acomp" to Pair("bcomp", "air"),
                    "bcomp" to Pair("acomp", "fire"),
                ),
                addons = emptyList(),
            ))
        }
    }

    @Test fun `throws when a component is undefined (not primal, not a compound key)`() {
        val ex = assertThrows(AspectDataError::class.java) {
            buildAspectData(BuildOptions(
                overrideCombinations = mapOf("x" to Pair("air", "doesnotexist")),
                addons = emptyList(),
            ))
        }
        assertTrue(ex.message?.contains("doesnotexist") == true)
    }

    @Test fun `throws when an aspect lacks a translation`() {
        val ex = assertThrows(AspectDataError::class.java) {
            buildAspectData(BuildOptions(
                overrideCombinations = mapOf("untranslated" to Pair("air", "fire")),
                addons = emptyList(),
            ))
        }
        assertTrue(ex.message?.contains("untranslated") == true)
    }

    // --- golden cross-check: Kotlin buildAspectData() must equal the TS oracle dump ---

    @Test fun `golden order equals oracle aspect-data json`() {
        val data = buildAspectData()
        val json = JsonParser.parseString(File("src/test/resources/golden/aspect-data.json").readText()).asJsonObject
        val oracleOrder = json.getAsJsonArray("order").map { it.asString }
        assertEquals(oracleOrder, data.order, "aspect order must match TS oracle")
    }

    @Test fun `golden universe equals oracle aspect-data json`() {
        val data = buildAspectData()
        val json = JsonParser.parseString(File("src/test/resources/golden/aspect-data.json").readText()).asJsonObject
        val oracleUniverse = json.getAsJsonArray("universe").map { it.asString }
        assertEquals(oracleUniverse, data.universe.toList(), "universe (insertion order) must match TS oracle")
    }

    @Test fun `golden combinations equal oracle aspect-data json`() {
        val data = buildAspectData()
        val json = JsonParser.parseString(File("src/test/resources/golden/aspect-data.json").readText()).asJsonObject
        val oracleCombos = json.getAsJsonObject("combinations")
        // Check same keys in same order
        val oracleKeys = oracleCombos.keySet().toList()
        assertEquals(oracleKeys, data.combinations.keys.toList(), "combination keys (order) must match TS oracle")
        // Check same component pairs
        for (key in oracleKeys) {
            val arr = oracleCombos.getAsJsonArray(key)
            val oracleC1 = arr[0].asString
            val oracleC2 = arr[1].asString
            val actual = data.combinations[key]!!
            assertEquals(oracleC1, actual.first, "combinations[$key].first")
            assertEquals(oracleC2, actual.second, "combinations[$key].second")
        }
    }

    @Test fun `golden primals equal oracle aspect-data json`() {
        val data = buildAspectData()
        val json = JsonParser.parseString(File("src/test/resources/golden/aspect-data.json").readText()).asJsonObject
        val oraclePrimals = json.getAsJsonArray("primals").map { it.asString }.toSet()
        assertEquals(oraclePrimals, data.primals, "primals must match TS oracle")
    }

    @Test fun `golden translate equals oracle aspect-data json`() {
        val data = buildAspectData()
        val json = JsonParser.parseString(File("src/test/resources/golden/aspect-data.json").readText()).asJsonObject
        val oracleTranslate = json.getAsJsonObject("translate")
        assertEquals(oracleTranslate.keySet().toSet(), data.translate.keys.toSet(), "translate key set must match TS oracle")
        for (key in oracleTranslate.keySet()) {
            assertEquals(oracleTranslate.get(key).asString, data.translate[key], "translate[$key]")
        }
    }
}
