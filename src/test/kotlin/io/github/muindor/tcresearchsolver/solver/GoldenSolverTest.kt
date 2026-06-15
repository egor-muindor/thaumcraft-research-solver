package io.github.muindor.tcresearchsolver.solver

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class GoldenSolverTest {
    private fun res(p: String) = File("src/test/resources/golden/$p").readText()

    @TestFactory
    fun `matches TS oracle on every scenario`(): List<DynamicTest> {
        val data = buildAspectData() // validated equal to oracle's by AspectDataTest golden assertion below
        val root = JsonParser.parseString(res("scenarios.json")).asJsonObject
        val scenarios = root.getAsJsonArray("scenarios")
        val results = root.getAsJsonObject("results")
        return scenarios.map { sEl ->
            val s = sEl.asJsonObject
            val name = s.get("name").asString
            DynamicTest.dynamicTest(name) {
                val b = createBoard(s.get("radius").asInt)
                for (cEl in s.getAsJsonArray("cells")) {
                    val c = cEl.asJsonObject
                    val (q, r) = c.get("key").asString.split(",").map { it.toInt() }
                    when (c.get("state").asString) {
                        "ANCHOR" -> setState(b, Hex(q, r), CellState.Anchor(c.get("aspect").asString))
                        "DEAD" -> setState(b, Hex(q, r), CellState.Dead)
                        else -> setState(b, Hex(q, r), CellState.Placed(c.get("aspect").asString, c.get("locked")?.asBoolean ?: false))
                    }
                }
                val invEl = s.get("inv")
                // Propagate threshold from the scenario fixture so Kotlin and TS use the same value.
                val threshold = s.get("threshold")?.takeIf { !it.isJsonNull }?.asInt ?: DEFAULT_THRESHOLD
                val inv = if (invEl.isJsonPrimitive)
                    makeInventory(data.universe.map { it to 100 }, threshold)
                else makeInventory(invEl.asJsonObject.entrySet().map { it.key to it.value.asInt }, threshold)
                val maxNodes = s.get("maxNodes").asInt
                val r = solveWithValidation(SolveOptions(
                    data = data, board = b, inventory = inv,
                    budget = SolveBudget(maxNodes, 3_600_000), now = { 0L }))
                val exp = results.getAsJsonObject(name)
                assertEquals(exp.get("status").asString, r.status.name, "$name status")
                val expScar = exp.get("scarcity").let { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) Double.POSITIVE_INFINITY else it.asDouble }
                assertEquals(expScar, r.cost?.scarcity ?: Double.POSITIVE_INFINITY, 0.0, "$name scarcity")
                if (!exp.get("cells").isJsonNull)
                    assertEquals(exp.get("cells").asDouble, r.cost?.cells, "$name cells")
                else
                    assertNull(r.cost, "$name cost must be null when golden cells is null")
            }
        }
    }
}
