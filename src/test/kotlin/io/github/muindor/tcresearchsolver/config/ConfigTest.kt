package io.github.muindor.tcresearchsolver.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The radius→timeout mapping is extracted as a pure function so it is unit-testable without
 * bootstrapping Forge (the `Configuration` parser NPEs outside FML). This guards against a
 * copy-paste mapping typo (e.g. radius 2 reading the R3 slot). The Forge load-wiring itself
 * (key names, defaults, ranges) is verified live in-game.
 */
class ConfigTest {

    @Test
    fun `perRadiusMs maps each radius to its own slot`() {
        assertEquals(2222, perRadiusMs(2, 2222, 3333, 4444, 5555))
        assertEquals(3333, perRadiusMs(3, 2222, 3333, 4444, 5555))
        assertEquals(4444, perRadiusMs(4, 2222, 3333, 4444, 5555))
        assertEquals(5555, perRadiusMs(5, 2222, 3333, 4444, 5555))
    }

    @Test
    fun `perRadiusMs returns 0 (no override) for radii outside 2 to 5`() {
        assertEquals(0, perRadiusMs(1, 2222, 3333, 4444, 5555))
        assertEquals(0, perRadiusMs(6, 2222, 3333, 4444, 5555))
    }
}
