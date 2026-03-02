package com.valerinsmp.vcompetition.listener;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.BlockKey;
import com.valerinsmp.vcompetition.model.ChallengeType;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

public final class CompetitionListener implements Listener {
    private final VCompetitionPlugin plugin;

    public CompetitionListener(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.isRuntimeActive()) {
            return;
        }
        if (plugin.isWorldExcluded(event.getBlockPlaced().getWorld().getName())) {
            return;
        }
        BlockKey blockKey = BlockKey.fromLocation(event.getBlockPlaced().getLocation());
        plugin.registerPlacedBlock(blockKey);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isRuntimeActive()) {
            return;
        }
        if (plugin.isWorldExcluded(event.getBlock().getWorld().getName())) {
            return;
        }
        ChallengeType active = plugin.getActiveChallenge();
        if (active != ChallengeType.MINING && active != ChallengeType.WOODCUTTING) {
            return;
        }

        BlockKey blockKey = BlockKey.fromLocation(event.getBlock().getLocation());
        if (plugin.consumeIfPlacedByPlayer(blockKey)) {
            return;
        }

        Material broken = event.getBlock().getType();
        if (active == ChallengeType.MINING && plugin.isMiningMaterial(broken)) {
            plugin.addPoints(event.getPlayer(), 1);
            return;
        }

        if (active == ChallengeType.WOODCUTTING && plugin.isWoodMaterial(broken)) {
            plugin.addPoints(event.getPlayer(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!plugin.isRuntimeActive()) {
            return;
        }
        if (plugin.isWorldExcluded(event.getPlayer().getWorld().getName())) {
            return;
        }
        if (plugin.getActiveChallenge() != ChallengeType.FISHING) {
            return;
        }
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (!(event.getCaught() instanceof Item item)) {
            return;
        }
        ItemStack stack = item.getItemStack();
        if (!plugin.isFishingMaterial(stack.getType())) {
            return;
        }

        if (event.getHook() != null && !event.getHook().isInOpenWater()) {
            return;
        }
        plugin.addPoints(event.getPlayer(), stack.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!plugin.isRuntimeActive()) {
            return;
        }
        if (plugin.isWorldExcluded(event.getLocation().getWorld().getName())) {
            return;
        }
        if (isNaturalSpawnReason(event.getSpawnReason())) {
            plugin.markNaturalEntity(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!plugin.isRuntimeActive()) {
            return;
        }
        if (plugin.isWorldExcluded(event.getEntity().getWorld().getName())) {
            return;
        }
        if (plugin.getActiveChallenge() != ChallengeType.SLAYER) {
            return;
        }

        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        if (plugin.isWorldExcluded(killer.getWorld().getName())) {
            return;
        }

        EntityType entityType = event.getEntityType();
        if (!plugin.isSlayerMob(entityType)) {
            return;
        }

        if (!plugin.isNaturalEntity(event.getEntity())) {
            return;
        }
        plugin.addPoints(killer, 1);
    }

    private boolean isNaturalSpawnReason(CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason) {
            case NATURAL, REINFORCEMENTS, JOCKEY, MOUNT, NETHER_PORTAL, PATROL, VILLAGE_INVASION -> true;
            default -> false;
        };
    }
}
