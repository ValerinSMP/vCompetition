package com.valerinsmp.vcompetition.service;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BossBarService {

    // slotId → (playerUUID → BossBar)
    private final Map<String, Map<UUID, BossBar>> slotBars = new ConcurrentHashMap<>();
    private final VCompetitionPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private BukkitTask tickTask;

    public BossBarService(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
        // Re-show bars for players already scoring in an active challenge (e.g. after /reload).
        for (Player player : Bukkit.getOnlinePlayers()) {
            reattachOnJoin(player);
        }
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        hideAll();
    }

    /** Called when a player scores their first (or any) point in a slot. */
    public void showOrUpdate(String slotId, UUID uuid) {
        if (!plugin.getConfig().getBoolean(configPrefix(slotId) + ".enabled", true)) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        Map<UUID, BossBar> bars = slotBars.computeIfAbsent(slotId, k -> new ConcurrentHashMap<>());
        BossBar bar = bars.get(uuid);
        if (bar == null) {
            bar = BossBar.bossBar(
                    buildTitle(slotId, uuid),
                    computeProgress(slotId, uuid),
                    loadColor(slotId),
                    loadOverlay(slotId)
            );
            bars.put(uuid, bar);
            player.showBossBar(bar);
        } else {
            updateBar(bar, slotId, uuid);
        }
    }

    /** Remove all bars for a slot (tournament ended). */
    public void hideSlotBars(String slotId) {
        Map<UUID, BossBar> bars = slotBars.remove(slotId);
        if (bars == null) return;
        bars.forEach((uuid, bar) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.hideBossBar(bar);
        });
    }

    /** Remove all bars for a player (quit). */
    public void hidePlayerBars(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        slotBars.forEach((slotId, bars) -> {
            BossBar bar = bars.remove(uuid);
            if (bar != null && player != null) player.hideBossBar(bar);
        });
    }

    /** Re-attach bars on reconnect for players who already have points. */
    public void reattachOnJoin(Player player) {
        UUID uuid = player.getUniqueId();
        for (String slotId : new java.util.ArrayList<>(slotBars.keySet())) {
            if (plugin.getPlayerPoints(slotId, uuid) > 0) {
                showOrUpdate(slotId, uuid);
            }
        }
        // Also check active slots not yet in slotBars
        for (String slotId : java.util.List.of(
                com.valerinsmp.vcompetition.service.CompetitionService.SLOT_DAILY,
                com.valerinsmp.vcompetition.service.CompetitionService.SLOT_SPECIAL)) {
            if (plugin.hasActiveChallenge(slotId) && plugin.getPlayerPoints(slotId, uuid) > 0) {
                showOrUpdate(slotId, uuid);
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void tick() {
        slotBars.forEach((slotId, bars) ->
                bars.forEach((uuid, bar) -> updateBar(bar, slotId, uuid)));
    }

    private void updateBar(BossBar bar, String slotId, UUID uuid) {
        bar.name(buildTitle(slotId, uuid));
        bar.progress(computeProgress(slotId, uuid));
    }

    private void hideAll() {
        slotBars.forEach((slotId, bars) -> bars.forEach((uuid, bar) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.hideBossBar(bar);
        }));
        slotBars.clear();
    }

    private Component buildTitle(String slotId, UUID uuid) {
        String template = loadTitleTemplate(slotId);

        int rank   = plugin.getPlayerPosition(slotId, uuid);
        int points = plugin.getPlayerPoints(slotId, uuid);

        VCompetitionPlugin.RankingEntry top1 = plugin.getCurrentTopAt(slotId, 1);
        String top1Name   = top1 != null ? top1.name()   : "---";
        int    top1Points = top1 != null ? top1.points()  : 0;

        String rankStr = rank > 0 ? String.valueOf(rank) : "?";

        return miniMessage.deserialize(template,
                Placeholder.unparsed("rank",         rankStr),
                Placeholder.unparsed("points",       String.valueOf(points)),
                Placeholder.unparsed("top1_name",    top1Name),
                Placeholder.unparsed("top1_points",  String.valueOf(top1Points)));
    }

    private float computeProgress(String slotId, UUID uuid) {
        VCompetitionPlugin.RankingEntry top1 = plugin.getCurrentTopAt(slotId, 1);
        if (top1 == null || top1.points() <= 0) return 1.0f;
        int playerPoints = plugin.getPlayerPoints(slotId, uuid);
        return Math.min(1.0f, Math.max(0.0f, (float) playerPoints / top1.points()));
    }

    private String configPrefix(String slotId) {
        return CompetitionService.SLOT_SPECIAL.equals(slotId) ? "bossbar.special" : "bossbar.daily";
    }

    private String loadTitleTemplate(String slotId) {
        String key = configPrefix(slotId) + ".title";
        return plugin.getConfig().getString(key,
                "<gray>#<yellow><rank></yellow> | <aqua><points> pts</aqua> | <gray>Top1: <gold><top1_name></gold> — <yellow><top1_points></yellow>");
    }

    private BossBar.Color loadColor(String slotId) {
        String raw = plugin.getConfig().getString(configPrefix(slotId) + ".color", "YELLOW");
        try {
            return BossBar.Color.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.YELLOW;
        }
    }

    private BossBar.Overlay loadOverlay(String slotId) {
        String raw = plugin.getConfig().getString(configPrefix(slotId) + ".overlay", "PROGRESS");
        try {
            return BossBar.Overlay.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Overlay.PROGRESS;
        }
    }
}
