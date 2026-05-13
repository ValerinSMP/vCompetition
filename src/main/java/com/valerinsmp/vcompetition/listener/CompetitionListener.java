package com.valerinsmp.vcompetition.listener;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.BlockKey;
import com.valerinsmp.vcompetition.model.ChallengeType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!plugin.isRuntimeActive()) return;
        if (plugin.isWorldExcluded(event.getPlayer().getWorld().getName())) return;
        if (!plugin.hasAnyChallengeOfType(ChallengeType.FISHING)) return;
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(event.getCaught() instanceof Item item)) return;
        if (event.getHook() != null && !event.getHook().isInOpenWater()) return;

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
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (!plugin.isRuntimeActive()) return;
        plugin.unmarkNaturalEntity(event.getEntity());
    }

    private boolean isNaturalSpawnReason(CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason) {
            case NATURAL, REINFORCEMENTS, JOCKEY, MOUNT, NETHER_PORTAL, PATROL, VILLAGE_INVASION -> true;
            default -> false;
        };
    }
}
