package com.valerinsmp.vcompetition;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Most overworld mobs spawn with SpawnReason.CHUNK_GEN (world/chunk generation), not NATURAL
 * (periodic tick-based spawning). Missing CHUNK_GEN meant most mobs encountered while exploring
 * fresh terrain silently didn't count for SLAYER — this is the "sometimes it counts, sometimes
 * it doesn't" bug reported for combat.
 */
class CombatSpawnReasonTest {

    private ServerMock server;
    private VCompetitionPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VCompetitionPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void chunkGenSpawnedMobCountsAsNatural() {
        Entity zombie = spawnWithReason(CreatureSpawnEvent.SpawnReason.CHUNK_GEN);
        assertTrue(plugin.isNaturalEntity(zombie),
                "a mob spawned via world/chunk generation must be treated as a natural (huntable) mob");
    }

    @Test
    void spawnerSpawnedMobDoesNotCountAsNatural() {
        Entity zombie = spawnWithReason(CreatureSpawnEvent.SpawnReason.SPAWNER);
        assertFalse(plugin.isNaturalEntity(zombie),
                "a mob spawned from a monster spawner must stay excluded (AFK-farm anti-exploit)");
    }

    private Entity spawnWithReason(CreatureSpawnEvent.SpawnReason reason) {
        WorldMock world = server.addSimpleWorld("world");
        Zombie zombie = (Zombie) world.spawn(new Location(world, 0, 70, 0), Zombie.class);
        server.getPluginManager().callEvent(new CreatureSpawnEvent(zombie, reason));
        return zombie;
    }
}
