package com.valerinsmp.vcompetition.listener;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.BlockKey;
import com.valerinsmp.vcompetition.model.ChallengeType;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.CaveVinesPlant;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public final class CompetitionListener implements Listener {
    private final VCompetitionPlugin plugin;

    public CompetitionListener(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.isRuntimeActive()) return;
        if (plugin.isWorldExcluded(event.getBlockPlaced().getWorld().getName())) return;
        plugin.registerPlacedBlock(BlockKey.fromLocation(event.getBlockPlaced().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isRuntimeActive()) return;
        if (plugin.isWorldExcluded(event.getBlock().getWorld().getName())) return;
        if (!plugin.hasAnyBlockChallenge()) return;

        BlockKey key = BlockKey.fromLocation(event.getBlock().getLocation());
        if (plugin.consumeIfPlacedByPlayer(key)) return;

        plugin.handleBlockBreak(event.getPlayer(), event.getBlock().getType());
    }

    /**
     * Cocoa, sweet berry bushes and cave vine glow berries are harvested by right-clicking
     * (the vanilla mechanic decrements age / clears the berry flag in place) — no BlockBreakEvent
     * fires unless the player fully breaks the plant instead. Without this, FARMING only ever
     * scored on the less common "break the whole plant" action.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.isRuntimeActive()) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.hasAnyChallengeOfType(ChallengeType.FARMING) && !plugin.hasAnyChallengeOfType(ChallengeType.OTONO)) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        if (plugin.isWorldExcluded(block.getWorld().getName())) return;
        if (!isRipeInteractHarvest(block)) return;

        plugin.handleBlockBreak(event.getPlayer(), block.getType());
    }

    private boolean isRipeInteractHarvest(Block block) {
        BlockData data = block.getBlockData();
        return switch (block.getType()) {
            case COCOA -> data instanceof Ageable ageable && ageable.getAge() == ageable.getMaximumAge();
            case SWEET_BERRY_BUSH -> data instanceof Ageable ageable && ageable.getAge() >= 2;
            case CAVE_VINES, CAVE_VINES_PLANT -> data instanceof CaveVinesPlant vine && vine.hasBerries();
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!plugin.isRuntimeActive()) return;
        if (plugin.isWorldExcluded(event.getPlayer().getWorld().getName())) return;
        if (!plugin.hasAnyChallengeOfType(ChallengeType.FISHING)) return;
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(event.getCaught() instanceof Item item)) return;

        ItemStack stack = item.getItemStack();
        plugin.handleFishCatch(event.getPlayer(), stack.getType(), stack.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!plugin.isRuntimeActive()) return;
        if (plugin.isWorldExcluded(event.getLocation().getWorld().getName())) return;
        if (isNaturalSpawnReason(event.getSpawnReason())) {
            plugin.markNaturalEntity(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!plugin.isRuntimeActive()) return;
        if (plugin.isWorldExcluded(event.getEntity().getWorld().getName())) return;
        if (!plugin.hasAnyChallengeOfType(ChallengeType.SLAYER)) return;

        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (plugin.isWorldExcluded(killer.getWorld().getName())) return;
        if (!plugin.isNaturalEntity(event.getEntity())) return;

        plugin.handleEntityKill(killer, event.getEntityType());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getBossBarService() != null) {
            plugin.getBossBarService().reattachOnJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.getBossBarService() != null) {
            plugin.getBossBarService().hidePlayerBars(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (!plugin.isRuntimeActive()) return;
        plugin.unmarkNaturalEntity(event.getEntity());
    }

    private boolean isNaturalSpawnReason(CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason) {
            // CHUNK_GEN is how most overworld/cave mobs actually spawn (world/chunk generation),
            // not NATURAL (which is only the periodic tick-based spawn) — missing it meant most
            // mobs encountered while exploring fresh terrain silently didn't count for SLAYER.
            case NATURAL, CHUNK_GEN, REINFORCEMENTS, JOCKEY, MOUNT, NETHER_PORTAL, PATROL,
                 VILLAGE_INVASION, RAID, SILVERFISH_BLOCK, SLIME_SPLIT, DROWNED -> true;
            default -> false;
        };
    }
}
