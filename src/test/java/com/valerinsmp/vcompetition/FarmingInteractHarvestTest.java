package com.valerinsmp.vcompetition;

import com.valerinsmp.vcompetition.model.ChallengeType;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cocoa (and sweet berry bushes / cave vines) are harvested by right-clicking, not by
 * breaking the block — BlockBreakEvent never fires for the common harvest action. This
 * covers the PlayerInteractEvent-based scoring path added to fix that gap.
 */
class FarmingInteractHarvestTest {

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
    void ripeCocoaRightClickScoresAPoint() {
        plugin.startAdminChallenge(ChallengeType.FARMING);
        PlayerMock player = server.addPlayer();
        Block block = ripeCocoaBlock();

        rightClick(player, block);

        assertEquals(1, plugin.getPlayerPoints(player.getUniqueId()),
                "harvesting a ripe cocoa pod via right-click must score a farming point");
    }

    @Test
    void unripeCocoaRightClickScoresNothing() {
        plugin.startAdminChallenge(ChallengeType.FARMING);
        PlayerMock player = server.addPlayer();
        WorldMock world = server.addSimpleWorld("world");
        Block block = world.getBlockAt(0, 70, 0);
        block.setType(Material.COCOA);
        Ageable cocoa = (Ageable) block.getBlockData();
        cocoa.setAge(0);
        block.setBlockData(cocoa);

        rightClick(player, block);

        assertEquals(0, plugin.getPlayerPoints(player.getUniqueId()),
                "right-clicking an unripe cocoa pod must not score");
    }

    @Test
    void ripeCocoaWithoutAnActiveFarmingChallengeScoresNothing() {
        Block block = ripeCocoaBlock();
        PlayerMock player = server.addPlayer();

        rightClick(player, block);

        assertEquals(0, plugin.getPlayerPoints(player.getUniqueId()));
    }

    private Block ripeCocoaBlock() {
        WorldMock world = server.addSimpleWorld("world");
        Block block = world.getBlockAt(0, 70, 0);
        block.setType(Material.COCOA);
        Ageable cocoa = (Ageable) block.getBlockData();
        cocoa.setAge(cocoa.getMaximumAge());
        block.setBlockData(cocoa);
        return block;
    }

    private void rightClick(PlayerMock player, Block block) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), block, BlockFace.UP);
        server.getPluginManager().callEvent(event);
    }
}
