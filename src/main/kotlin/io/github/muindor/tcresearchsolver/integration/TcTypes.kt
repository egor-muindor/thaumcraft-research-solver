package io.github.muindor.tcresearchsolver.integration

/**
 * Typed accessors for Thaumcraft's live aspect registry.
 *
 * This file is the **only** place in the integration layer that imports TC classes.
 * Keeping TC imports isolated here lets [buildAspectDataFrom] and [RegistryEntry] remain
 * TC-free and unit-testable without a live game instance.
 *
 * Signatures confirmed via `javap` on `reference/jars/Thaumcraft-1.7.10-4.2.3.5a.jar`
 * (pinned 2026-06-15, Task 3.1).
 *
 *   public static LinkedHashMap<String, Aspect> Aspect.aspects
 *   public String  Aspect.getTag()
 *   public Aspect[] Aspect.getComponents()   // length 0 (primal) or 2 (compound)
 *   public boolean Aspect.isPrimal()
 */
fun liveRegistryEntries(): List<RegistryEntry> =
    thaumcraft.api.aspects.Aspect.aspects.values.map { a ->
        RegistryEntry(
            tag = a.tag,
            components = a.components?.map { it.tag } ?: emptyList(),
            isPrimal = a.isPrimal,
        )
    }
