package com.valerinsmp.vcompetition.service;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.BlockKey;
import com.valerinsmp.vcompetition.model.ChallengeType;
import com.valerinsmp.vcompetition.storage.SQLiteManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class CompetitionService {
    private final VCompetitionPlugin plugin;
    private final SQLiteManager sqliteManager;

    private final Map<UUID, Integer> scores = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> winsTotal = new ConcurrentHashMap<>();
    private final Map<ChallengeType, Map<UUID, Integer>> winsByChallenge = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastOutrankGlobalByActor = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastOutrankPrivateByVictim = new ConcurrentHashMap<>();
    private final Set<BlockKey> placedBlocks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> naturalEntities = ConcurrentHashMap.newKeySet();

    private volatile Set<Material> miningMaterials = EnumSet.noneOf(Material.class);
    private volatile Set<Material> woodcuttingMaterials = EnumSet.noneOf(Material.class);
    private volatile Set<Material> fishingMaterials = EnumSet.noneOf(Material.class);
    private volatile Set<EntityType> slayerMobs = EnumSet.noneOf(EntityType.class);
    private volatile Set<String> excludedWorlds = Set.of();
    private volatile long playtimeIntervalTicks = 1200L;
    private volatile int playtimePointsPerInterval = 1;
    private volatile long outrankGlobalCooldownMillis = 15000L;
    private volatile long outrankServerGlobalCooldownMillis = 8000L;
    private volatile long outrankPrivateCooldownMillis = 30000L;
    private volatile int outrankSummaryThreshold = 3;
    private volatile int outrankMaxPrivateVictimsPerEvent = 2;
    private volatile int outrankGlobalTopCutoff = 3;
    private volatile long lastOutrankGlobalServerAt;
    private volatile boolean runtimeActive;

    private ChallengeType activeChallenge;
    private long challengeStart;
    private long challengeEnd;
    private boolean scheduleManagedChallenge;
    private BukkitTask endTask;
    private BukkitTask playtimeTask;

    public CompetitionService(VCompetitionPlugin plugin, SQLiteManager sqliteManager) {
        this.plugin = plugin;
        this.sqliteManager = sqliteManager;
    }

    public void enableRuntime() {
        runtimeActive = true;
    }

    public void disableRuntime() {
        runtimeActive = false;
        stopPlaytimeTicker();
    }

    public boolean isRuntimeActive() {
        return runtimeActive;
    }

    public void shutdownAndPersist() {
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        stopPlaytimeTicker();

        List<CompletableFuture<Void>> pending = new ArrayList<>();
        if (activeChallenge != null) {
            pending.add(sqliteManager.saveChallengeState(activeChallenge, challengeStart, challengeEnd, true));
            for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {
                UUID uuid = entry.getKey();
                pending.add(sqliteManager.upsertPlayerScore(activeChallenge, uuid, names.getOrDefault(uuid, "Unknown"), entry.getValue()));
            }
        } else {
            pending.add(sqliteManager.saveChallengeState(null, 0L, 0L, false));
        }

        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).join();
        } catch (Exception exception) {
            plugin.getLogger().warning("Error durante guardado en onDisable: " + exception.getMessage());
        }

        naturalEntities.clear();
        placedBlocks.clear();
        lastOutrankGlobalByActor.clear();
        lastOutrankPrivateByVictim.clear();
        lastOutrankGlobalServerAt = 0L;
        scores.clear();
        names.clear();
    }

    public void loadPersistentState() {
        try {
            placedBlocks.addAll(sqliteManager.loadPlacedBlocks().join());

            Map<UUID, SQLiteManager.PlayerWins> globalWins = sqliteManager.loadWinsTotal().join();
            for (Map.Entry<UUID, SQLiteManager.PlayerWins> entry : globalWins.entrySet()) {
                winsTotal.put(entry.getKey(), entry.getValue().wins());
                names.put(entry.getKey(), entry.getValue().playerName());
            }

            for (ChallengeType type : ChallengeType.values()) {
                Map<UUID, SQLiteManager.PlayerWins> challengeWins = sqliteManager.loadWinsByChallenge(type).join();
                Map<UUID, Integer> local = new ConcurrentHashMap<>();
                for (Map.Entry<UUID, SQLiteManager.PlayerWins> entry : challengeWins.entrySet()) {
                    local.put(entry.getKey(), entry.getValue().wins());
                    names.put(entry.getKey(), entry.getValue().playerName());
                }
                winsByChallenge.put(type, local);
            }

            SQLiteManager.ChallengeStateSnapshot snapshot = sqliteManager.loadChallengeState().join();
            if (snapshot.active() && snapshot.challengeType() != null) {
                activeChallenge = snapshot.challengeType();
                challengeStart = snapshot.startTime();
                challengeEnd = snapshot.endTime();

                Map<UUID, SQLiteManager.PlayerScore> loaded = sqliteManager.loadProgress(activeChallenge).join();
                loaded.forEach((uuid, score) -> {
                    scores.put(uuid, score.points());
                    names.put(uuid, score.playerName());
                });
                scheduleChallengeEnd();
                startPlaytimeTickerIfNeeded();
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("No se pudo restaurar estado: " + exception.getMessage());
        }
    }

    public boolean hasActiveChallenge() {
        return activeChallenge != null;
    }

    public boolean isScheduleManagedChallengeActive() {
        return hasActiveChallenge() && scheduleManagedChallenge;
    }

    public ChallengeType getActiveChallenge() {
        return activeChallenge;
    }

    public long getRemainingMillis() {
        if (activeChallenge == null) {
            return 0L;
        }
        return Math.max(0L, challengeEnd - System.currentTimeMillis());
    }

    public int getPlayerPoints(UUID uuid) {
        return scores.getOrDefault(uuid, 0);
    }

    public int getPlayerPosition(UUID uuid) {
        List<UUID> ordered = rankOrdered();
        int index = ordered.indexOf(uuid);
        return index < 0 ? -1 : index + 1;
    }

    public int getGapTopOneToTwo() {
        List<VCompetitionPlugin.RankingEntry> ranking = getRankingEntries();
        if (ranking.size() < 2) {
            return ranking.isEmpty() ? 0 : ranking.get(0).points();
        }
        return ranking.get(0).points() - ranking.get(1).points();
    }

    public int getGapToAbove(UUID uuid) {
        List<UUID> ordered = rankOrdered();
        int index = ordered.indexOf(uuid);
        if (index <= 0) {
            return 0;
        }
        UUID above = ordered.get(index - 1);
        return Math.max(0, scores.getOrDefault(above, 0) - scores.getOrDefault(uuid, 0));
    }

    public int getGapToBelow(UUID uuid) {
        List<UUID> ordered = rankOrdered();
        int index = ordered.indexOf(uuid);
        if (index < 0 || index >= ordered.size() - 1) {
            return 0;
        }
        UUID below = ordered.get(index + 1);
        return Math.max(0, scores.getOrDefault(uuid, 0) - scores.getOrDefault(below, 0));
    }

    public int getWinsTotal(UUID uuid) {
        return winsTotal.getOrDefault(uuid, 0);
    }

    public int getWinsByChallenge(UUID uuid, ChallengeType challengeType) {
        return winsByChallenge.getOrDefault(challengeType, Collections.emptyMap()).getOrDefault(uuid, 0);
    }

    public VCompetitionPlugin.RankingEntry getCurrentTopAt(int rank) {
        List<VCompetitionPlugin.RankingEntry> ranking = getRankingEntries();
        if (rank < 1 || rank > ranking.size()) {
            return null;
        }
        return ranking.get(rank - 1);
    }

    public VCompetitionPlugin.WinsEntry getGlobalWinsTopAt(int rank) {
        List<VCompetitionPlugin.WinsEntry> ranking = getGlobalWinsRanking();
        if (rank < 1 || rank > ranking.size()) {
            return null;
        }
        return ranking.get(rank - 1);
    }

    public VCompetitionPlugin.WinsEntry getChallengeWinsTopAt(ChallengeType challengeType, int rank) {
        List<VCompetitionPlugin.WinsEntry> ranking = getChallengeWinsRanking(challengeType);
        if (rank < 1 || rank > ranking.size()) {
            return null;
        }
        return ranking.get(rank - 1);
    }

    public void registerPlacedBlock(BlockKey blockKey) {
        if (!runtimeActive) {
            return;
        }
        if (placedBlocks.add(blockKey)) {
            sqliteManager.addPlacedBlock(blockKey);
        }
    }

    public boolean consumeIfPlacedByPlayer(BlockKey blockKey) {
        if (!runtimeActive) {
            return false;
        }
        if (!placedBlocks.remove(blockKey)) {
            return false;
        }
        sqliteManager.removePlacedBlock(blockKey);
        return true;
    }

    public void markNaturalEntity(Entity entity) {
        if (!runtimeActive) {
            return;
        }
        naturalEntities.add(entity.getUniqueId());
    }

    public boolean isNaturalEntity(Entity entity) {
        if (!runtimeActive) {
            return false;
        }
        return naturalEntities.remove(entity.getUniqueId());
    }

    public boolean isMiningMaterial(Material material) {
        return miningMaterials.contains(material);
    }

    public boolean isWoodMaterial(Material material) {
        return woodcuttingMaterials.contains(material);
    }

    public boolean isFishingMaterial(Material material) {
        return fishingMaterials.contains(material);
    }

    public boolean isSlayerMob(EntityType entityType) {
        return slayerMobs.contains(entityType);
    }

    public boolean isWorldExcluded(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        return excludedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public void addPoints(Player player, int amount) {
        if (activeChallenge == null || amount <= 0 || player == null) {
            return;
        }
        if (isWorldExcluded(player.getWorld().getName())) {
            return;
        }
        UUID uuid = player.getUniqueId();
        names.put(uuid, player.getName());

        List<UUID> before = rankOrdered();
        int updated = scores.getOrDefault(uuid, 0) + amount;
        scores.put(uuid, updated);
        sqliteManager.upsertPlayerScore(activeChallenge, uuid, player.getName(), updated);

        notifyOutranks(uuid, before, rankOrdered());
    }

    public boolean handlePointEdit(CommandSender sender, String[] args, String label, VCompetitionPlugin.PointOperation operation) {
        if (args.length < 4) {
            plugin.getMessageService().sendPath(sender, "messages.point-edit.usage", List.of("&cUso: /%label% admin %operation% <jugador> <puntos>"),
                    plugin.getMessageService().placeholders("%label%", label, "%operation%", operation.commandName()));
            return true;
        }
        if (!hasActiveChallenge()) {
            plugin.getMessageService().sendPath(sender, "messages.point-edit.no-active", List.of("&cNo hay torneo activo."), Map.of());
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        int points;
        try {
            points = Integer.parseInt(args[3]);
        } catch (NumberFormatException exception) {
            plugin.getMessageService().sendPath(sender, "messages.point-edit.invalid-points", List.of("&cPuntos inválidos."), Map.of());
            return true;
        }

        UUID uuid = target.getUniqueId();
        if (uuid == null) {
            plugin.getMessageService().sendPath(sender, "messages.point-edit.invalid-player", List.of("&cJugador inválido."), Map.of());
            return true;
        }
        String targetName = target.getName() == null ? args[2] : target.getName();

        List<UUID> before = rankOrdered();
        int base = scores.getOrDefault(uuid, 0);
        int safePoints = Math.max(0, points);
        int updated = switch (operation) {
            case SET -> safePoints;
            case ADD -> base + safePoints;
            case REMOVE -> Math.max(0, base - safePoints);
        };

        scores.put(uuid, updated);
        names.put(uuid, targetName);
        sqliteManager.upsertPlayerScore(activeChallenge, uuid, targetName, updated);
        notifyOutranks(uuid, before, rankOrdered());

        plugin.getMessageService().sendPath(sender, "messages.point-edit.updated", List.of("&aPuntaje actualizado: &e%player% &7-> &b%points%"),
            plugin.getMessageService().placeholders("%player%", targetName, "%points%", String.valueOf(updated)));
        return true;
    }

    public void startAdminChallenge(ChallengeType type) {
        scheduleManagedChallenge = false;
        startChallenge(type, true);
    }

    public void stopAdminChallenge() {
        scheduleManagedChallenge = false;
        stopChallenge(true);
    }

    public void startScheduledChallenge(ChallengeType type) {
        scheduleManagedChallenge = true;
        startChallenge(type, true);
    }

    public void stopScheduledChallenge() {
        stopChallenge(true);
    }

    public Component statusLine() {
        if (!hasActiveChallenge()) {
            String line = plugin.getMessageService().getLines("messages.status-no-active", List.of("&cNo hay torneo activo.")).stream().findFirst().orElse("&cNo hay torneo activo.");
            return plugin.getMessageService().renderPrefixed(line);
        }
        String msg = plugin.getMessageService().getLines("messages.status-active", List.of("&aTorneo activo: %challenge% | %time_left%")).stream().findFirst().orElse("&aTorneo activo: %challenge% | %time_left%");
        msg = plugin.getMessageService().applyPlaceholders(msg,
            plugin.getMessageService().placeholders("%challenge%", activeChallenge.displayName(), "%time_left%", formatDuration(getRemainingMillis())));
        return plugin.getMessageService().renderPrefixed(msg);
    }

    public void sendTop(CommandSender sender) {
        plugin.getMessageService().sendPath(sender, "messages.top-header", List.of("&7Top"), Map.of());
        List<VCompetitionPlugin.RankingEntry> top = getRankingEntries();
        if (top.isEmpty()) {
            plugin.getMessageService().sendPath(sender, "messages.top-empty", List.of("&7Sin participantes"), Map.of());
            return;
        }
        int max = Math.min(10, top.size());
        for (int i = 0; i < max; i++) {
            VCompetitionPlugin.RankingEntry entry = top.get(i);
            plugin.getMessageService().sendPath(sender, "messages.top-line", List.of("#%rank% %player% %points%"),
                    plugin.getMessageService().placeholders("%rank%", String.valueOf(i + 1), "%player%", entry.name(), "%points%", String.valueOf(entry.points())));
        }
    }

    public void updateDurationDays(long days) {
        plugin.getConfig().set("challenge.duration-days", days);
        plugin.saveConfig();

        if (activeChallenge != null) {
            challengeEnd = challengeStart + (days * 24L * 60L * 60L * 1000L);
            scheduleChallengeEnd();
            sqliteManager.saveChallengeState(activeChallenge, challengeStart, challengeEnd, true);
        }
    }

    public void resetPlacedCache() {
        placedBlocks.clear();
        sqliteManager.clearPlacedBlocks();
    }

    public void sendDebug(CommandSender sender, boolean papiRegistered) {
        plugin.getMessageService().sendPath(sender, "messages.debug.header", List.of("&7------ &bvCompetition Debug &7------"), Map.of());
        plugin.getMessageService().sendPath(sender, "messages.debug.db-connected", List.of("&fDB conectada: &a%value%"), plugin.getMessageService().placeholders("%value%", String.valueOf(sqliteManager.isConnected())));
        plugin.getMessageService().sendPath(sender, "messages.debug.db-active-tasks", List.of("&fDB tareas activas: &e%value%"), plugin.getMessageService().placeholders("%value%", String.valueOf(sqliteManager.getActiveTasks())));
        plugin.getMessageService().sendPath(sender, "messages.debug.db-queue-size", List.of("&fDB cola pendiente: &e%value%"), plugin.getMessageService().placeholders("%value%", String.valueOf(sqliteManager.getQueueSize())));
        plugin.getMessageService().sendPath(sender, "messages.debug.db-pending-futures", List.of("&fDB futures pendientes: &e%value%"), plugin.getMessageService().placeholders("%value%", String.valueOf(sqliteManager.getPendingTasks())));
        plugin.getMessageService().sendPath(sender, "messages.debug.challenge-active", List.of("&fTorneo activo: &a%value%"), plugin.getMessageService().placeholders("%value%", activeChallenge == null ? "none" : activeChallenge.name()));
        plugin.getMessageService().sendPath(sender, "messages.debug.participants", List.of("&fParticipantes cargados: &b%value%"), plugin.getMessageService().placeholders("%value%", String.valueOf(scores.size())));
        plugin.getMessageService().sendPath(sender, "messages.debug.placed-cache", List.of("&fPlaced blocks cache: &d%value%"), plugin.getMessageService().placeholders("%value%", String.valueOf(placedBlocks.size())));
        plugin.getMessageService().sendPath(sender, "messages.debug.papi-registered", List.of("&fPAPI registrado: &6%value%"), plugin.getMessageService().placeholders("%value%", String.valueOf(papiRegistered)));
        plugin.getMessageService().sendPath(sender, "messages.debug.end-task-active", List.of("&fTask fin torneo activa: &6%value%"), plugin.getMessageService().placeholders("%value%", String.valueOf(endTask != null && !endTask.isCancelled())));
        plugin.getMessageService().sendPath(sender, "messages.debug.schedule-managed", List.of("&fSchedule managed: &b%value%"), plugin.getMessageService().placeholders("%value%", String.valueOf(scheduleManagedChallenge)));
    }

    public void loadCompetitionRules() {
        miningMaterials = loadMaterials("competition-types.mining.materials");
        woodcuttingMaterials = loadMaterials("competition-types.woodcutting.materials");
        fishingMaterials = loadMaterials("competition-types.fishing.materials");
        slayerMobs = loadEntityTypes("competition-types.slayer.mobs");
        long intervalSeconds = Math.max(10L, plugin.getConfig().getLong("competition-types.playtime.interval-seconds", 60L));
        playtimeIntervalTicks = intervalSeconds * 20L;
        playtimePointsPerInterval = Math.max(1, plugin.getConfig().getInt("competition-types.playtime.points-per-interval", 1));
        excludedWorlds = plugin.getConfig().getStringList("excluded-worlds").stream()
            .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        outrankGlobalCooldownMillis = Math.max(0L, plugin.getConfig().getLong("notifications.outrank.global-cooldown-ms", 15000L));
        outrankServerGlobalCooldownMillis = Math.max(0L, plugin.getConfig().getLong("notifications.outrank.global-server-cooldown-ms", 8000L));
        outrankPrivateCooldownMillis = Math.max(0L, plugin.getConfig().getLong("notifications.outrank.private-cooldown-ms", 30000L));
        outrankSummaryThreshold = Math.max(1, plugin.getConfig().getInt("notifications.outrank.summary-threshold", 3));
        outrankMaxPrivateVictimsPerEvent = Math.max(0, plugin.getConfig().getInt("notifications.outrank.max-private-victims-per-event", 2));
        outrankGlobalTopCutoff = Math.max(1, plugin.getConfig().getInt("notifications.outrank.global-top-cutoff", 3));
        startPlaytimeTickerIfNeeded();
    }

    private Set<Material> loadMaterials(String path) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String raw : plugin.getConfig().getStringList(path)) {
            Material material = Material.matchMaterial(raw);
            if (material == null) {
                plugin.getLogger().warning("Material inválido en " + path + ": " + raw);
                continue;
            }
            set.add(material);
        }
        return Collections.unmodifiableSet(set);
    }

    private Set<EntityType> loadEntityTypes(String path) {
        Set<EntityType> set = EnumSet.noneOf(EntityType.class);
        for (String raw : plugin.getConfig().getStringList(path)) {
            try {
                set.add(EntityType.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Mob inválido en " + path + ": " + raw);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    private void startChallenge(ChallengeType challengeType, boolean announce) {
        if (activeChallenge != null) {
            stopChallenge(false);
        }

        activeChallenge = challengeType;
        challengeStart = System.currentTimeMillis();
        challengeEnd = challengeStart + (plugin.getConfig().getLong("challenge.duration-days", 7L) * 24L * 60L * 60L * 1000L);

        scores.clear();
        names.clear();
        sqliteManager.clearProgress(challengeType);
        sqliteManager.saveChallengeState(challengeType, challengeStart, challengeEnd, true);

        scheduleChallengeEnd();
        startPlaytimeTickerIfNeeded();

        if (announce) {
            plugin.getMessageService().broadcastPath("messages.challenge-start", List.of("<green>Inició %challenge%</green>"),
                    plugin.getMessageService().placeholders("%challenge%", challengeType.displayName()));
            if (plugin.getSoundService() != null) {
                plugin.getSoundService().playTournamentStart();
            }
        }
    }

    private void stopChallenge(boolean announceAndReward) {
        ChallengeType ending = activeChallenge;
        if (ending == null) {
            return;
        }

        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }

        if (announceAndReward) {
            plugin.getMessageService().broadcastPath("messages.challenge-end", List.of("<red>Terminó %challenge%</red>"),
                    plugin.getMessageService().placeholders("%challenge%", ending.displayName()));
            if (plugin.getSoundService() != null) {
                plugin.getSoundService().playTournamentEnd();
            }
        }

        List<VCompetitionPlugin.RankingEntry> ranking = getRankingEntries();
        if (announceAndReward) {
            applyRewardsAndAnnounceWinners(ending, ranking);
        }

        activeChallenge = null;
        challengeStart = 0L;
        challengeEnd = 0L;
        scheduleManagedChallenge = false;
        stopPlaytimeTicker();
        scores.clear();
        names.clear();
        lastOutrankGlobalByActor.clear();
        lastOutrankPrivateByVictim.clear();
        lastOutrankGlobalServerAt = 0L;

        sqliteManager.saveChallengeState(null, 0L, 0L, false);
    }

    private void applyRewardsAndAnnounceWinners(ChallengeType challengeType, List<VCompetitionPlugin.RankingEntry> ranking) {
        int limit = Math.min(3, ranking.size());
        for (int i = 0; i < limit; i++) {
            VCompetitionPlugin.RankingEntry entry = ranking.get(i);
            List<String> commands = plugin.getConfig().getStringList("rewards.top" + (i + 1) + ".commands");
            for (String cmd : commands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", entry.name()));
            }
        }

        if (!ranking.isEmpty()) {
            VCompetitionPlugin.RankingEntry winner = ranking.get(0);
            long now = System.currentTimeMillis();

            winsTotal.put(winner.uuid(), winsTotal.getOrDefault(winner.uuid(), 0) + 1);
            Map<UUID, Integer> localWins = winsByChallenge.computeIfAbsent(challengeType, unused -> new ConcurrentHashMap<>());
            localWins.put(winner.uuid(), localWins.getOrDefault(winner.uuid(), 0) + 1);
            names.put(winner.uuid(), winner.name());
            sqliteManager.recordWinner(challengeType, winner.uuid(), winner.name(), winner.points(), now);

            plugin.getMessageService().broadcastPath("messages.winner-global", List.of("<gold>Ganador %player%</gold>"),
                    plugin.getMessageService().placeholders("%challenge%", challengeType.displayName(), "%player%", winner.name(), "%points%", String.valueOf(winner.points())));

                int topLimit = Math.min(3, ranking.size());
                for (int i = 0; i < topLimit; i++) {
                VCompetitionPlugin.RankingEntry entry = ranking.get(i);
                plugin.getMessageService().broadcastPath("messages.winner-top-line", List.of("%prefix%<gray>Top #%rank%: <yellow>%player%</yellow> - <aqua>%points%</aqua></gray>"),
                    plugin.getMessageService().placeholders(
                        "%rank%", String.valueOf(i + 1),
                        "%player%", entry.name(),
                        "%points%", String.valueOf(entry.points())
                    ));
                }

            Player onlineWinner = Bukkit.getPlayer(winner.uuid());
            if (onlineWinner != null) {
                plugin.getMessageService().sendPath(onlineWinner, "messages.winner-private", List.of("<green>Ganaste</green>"),
                        plugin.getMessageService().placeholders("%challenge%", challengeType.displayName(), "%player%", winner.name(), "%points%", String.valueOf(winner.points())));
            }
        }
    }

    private void scheduleChallengeEnd() {
        if (endTask != null) {
            endTask.cancel();
        }
        long remainingMillis = challengeEnd - System.currentTimeMillis();
        long ticks = Math.max(20L, remainingMillis / 50L);
        endTask = Bukkit.getScheduler().runTaskLater(plugin, () -> stopChallenge(true), ticks);
    }

    private void startPlaytimeTickerIfNeeded() {
        if (!runtimeActive || activeChallenge != ChallengeType.PLAYTIME) {
            stopPlaytimeTicker();
            return;
        }
        if (playtimeTask != null && !playtimeTask.isCancelled()) {
            return;
        }

        long interval = Math.max(20L, playtimeIntervalTicks);
        playtimeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!runtimeActive || activeChallenge != ChallengeType.PLAYTIME) {
                stopPlaytimeTicker();
                return;
            }
            addPlaytimePointsBatch(playtimePointsPerInterval);
        }, interval, interval);
    }

    private void addPlaytimePointsBatch(int amount) {
        if (activeChallenge != ChallengeType.PLAYTIME || amount <= 0) {
            return;
        }

        List<Player> onlinePlayers = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                onlinePlayers.add(player);
            }
        }
        if (onlinePlayers.isEmpty()) {
            return;
        }

        List<UUID> before = rankOrdered();
        for (Player player : onlinePlayers) {
            if (isWorldExcluded(player.getWorld().getName())) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            names.put(uuid, player.getName());
            int updated = scores.getOrDefault(uuid, 0) + amount;
            scores.put(uuid, updated);
            sqliteManager.upsertPlayerScore(activeChallenge, uuid, player.getName(), updated);
        }
        List<UUID> after = rankOrdered();

        for (Player player : onlinePlayers) {
            if (isWorldExcluded(player.getWorld().getName())) {
                continue;
            }
            notifyOutranks(player.getUniqueId(), before, after);
        }
    }

    private void stopPlaytimeTicker() {
        if (playtimeTask != null) {
            playtimeTask.cancel();
            playtimeTask = null;
        }
    }

    public List<VCompetitionPlugin.RankingEntry> getRankingEntries() {
        List<VCompetitionPlugin.RankingEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {
            UUID uuid = entry.getKey();
            entries.add(new VCompetitionPlugin.RankingEntry(uuid, names.getOrDefault(uuid, "Unknown"), entry.getValue()));
        }
        entries.sort(Comparator.comparingInt(VCompetitionPlugin.RankingEntry::points).reversed().thenComparing(VCompetitionPlugin.RankingEntry::name));
        return entries;
    }

    private List<VCompetitionPlugin.WinsEntry> getGlobalWinsRanking() {
        List<VCompetitionPlugin.WinsEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : winsTotal.entrySet()) {
            UUID uuid = entry.getKey();
            entries.add(new VCompetitionPlugin.WinsEntry(uuid, names.getOrDefault(uuid, "Unknown"), entry.getValue()));
        }
        entries.sort(Comparator.comparingInt(VCompetitionPlugin.WinsEntry::wins).reversed().thenComparing(VCompetitionPlugin.WinsEntry::name));
        return entries;
    }

    private List<VCompetitionPlugin.WinsEntry> getChallengeWinsRanking(ChallengeType challengeType) {
        Map<UUID, Integer> map = winsByChallenge.getOrDefault(challengeType, Collections.emptyMap());
        List<VCompetitionPlugin.WinsEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : map.entrySet()) {
            UUID uuid = entry.getKey();
            entries.add(new VCompetitionPlugin.WinsEntry(uuid, names.getOrDefault(uuid, "Unknown"), entry.getValue()));
        }
        entries.sort(Comparator.comparingInt(VCompetitionPlugin.WinsEntry::wins).reversed().thenComparing(VCompetitionPlugin.WinsEntry::name));
        return entries;
    }

    private List<UUID> rankOrdered() {
        List<UUID> ordered = new ArrayList<>(scores.keySet());
        ordered.sort((a, b) -> {
            int scoreCmp = Integer.compare(scores.getOrDefault(b, 0), scores.getOrDefault(a, 0));
            if (scoreCmp != 0) {
                return scoreCmp;
            }
            return names.getOrDefault(a, "").compareToIgnoreCase(names.getOrDefault(b, ""));
        });
        return ordered;
    }

    private void notifyOutranks(UUID actor, List<UUID> before, List<UUID> after) {
        if (activeChallenge == null) {
            return;
        }

        int oldIndex = before.indexOf(actor);
        int newIndex = after.indexOf(actor);
        if (newIndex < 0 || oldIndex < 0 || newIndex >= oldIndex) {
            return;
        }
        String rankText = String.valueOf(newIndex + 1);

        Set<UUID> victims = new HashSet<>(before.subList(newIndex, oldIndex));
        victims.remove(actor);

        if (victims.isEmpty()) {
            return;
        }

        String actorName = names.getOrDefault(actor, "Unknown");
        long now = System.currentTimeMillis();
        int actorPoints = scores.getOrDefault(actor, 0);
        int previousCutoffScore = scoreAtRank(before, outrankGlobalTopCutoff);
        int newRank = newIndex + 1;

        Player actorPlayer = Bukkit.getPlayer(actor);
        if (newIndex + 1 > outrankGlobalTopCutoff) {
            if (actorPlayer != null && actorPlayer.isOnline()) {
                plugin.getMessageService().sendPath(actorPlayer, "messages.outrank-self", List.of("%prefix%<gray>Subiste al puesto <yellow>#%rank%</yellow> superando a <yellow>%count%</yellow> jugadores.</gray>"),
                        plugin.getMessageService().placeholders(
                                "%challenge%", activeChallenge.displayName(),
                                "%player%", actorName,
                                "%rank%", String.valueOf(newIndex + 1),
                                "%count%", String.valueOf(victims.size())
                        ));
                if (plugin.getSoundService() != null) {
                    plugin.getSoundService().playOutrank(actorPlayer, null);
                }
            }
            return;
        }

        if (previousCutoffScore != Integer.MIN_VALUE && actorPoints <= previousCutoffScore) {
            if (actorPlayer != null && actorPlayer.isOnline()) {
                plugin.getMessageService().sendPath(actorPlayer, "messages.outrank-self", List.of("%prefix%<gray>Subiste al puesto <yellow>#%rank%</yellow> superando a <yellow>%count%</yellow> jugadores.</gray>"),
                        plugin.getMessageService().placeholders(
                                "%challenge%", activeChallenge.displayName(),
                                "%player%", actorName,
                                "%rank%", String.valueOf(newIndex + 1),
                                "%count%", String.valueOf(victims.size())
                        ));
                if (plugin.getSoundService() != null) {
                    plugin.getSoundService().playOutrank(actorPlayer, null);
                }
            }
            return;
        }

        int privateSent = 0;
        for (UUID victimUuid : victims) {
            String victimName = names.getOrDefault(victimUuid, "Unknown");
            Player victim = Bukkit.getPlayer(victimUuid);
            if (victim != null && victim.isOnline() && privateSent < outrankMaxPrivateVictimsPerEvent) {
                if (now - lastOutrankPrivateByVictim.getOrDefault(victimUuid, 0L) < outrankPrivateCooldownMillis) {
                    continue;
                }
                plugin.getMessageService().sendPath(victim, "messages.outrank-private", List.of("<yellow>Superado por %player%</yellow>"),
                    plugin.getMessageService().placeholders("%challenge%", activeChallenge.displayName(), "%player%", actorName, "%victim%", victimName, "%rank%", rankText));
                lastOutrankPrivateByVictim.put(victimUuid, now);
                privateSent++;
            }
        }

        if (now - lastOutrankGlobalByActor.getOrDefault(actor, 0L) < outrankGlobalCooldownMillis) {
            return;
        }
        if (now - lastOutrankGlobalServerAt < outrankServerGlobalCooldownMillis) {
            return;
        }

        if (victims.size() >= outrankSummaryThreshold) {
            plugin.getMessageService().broadcastPath("messages.outrank-global-summary", List.of("%prefix%<aqua>%player%</aqua> <gray>subió al puesto <yellow>#%rank%</yellow> superando a <yellow>%count%</yellow> jugadores.</gray>"),
                    plugin.getMessageService().placeholders(
                            "%challenge%", activeChallenge.displayName(),
                            "%player%", actorName,
                            "%rank%", String.valueOf(newIndex + 1),
                            "%count%", String.valueOf(victims.size())
                    ));
        } else {
            for (UUID victimUuid : victims) {
                String victimName = names.getOrDefault(victimUuid, "Unknown");
                plugin.getMessageService().broadcastPath("messages.outrank-global", List.of("<aqua>%player% superó a %victim%</aqua>"),
                    plugin.getMessageService().placeholders("%challenge%", activeChallenge.displayName(), "%player%", actorName, "%victim%", victimName, "%rank%", rankText));
            }
        }

        if (plugin.getSoundService() != null && actorPlayer != null) {
            plugin.getSoundService().playOutrank(actorPlayer, null);
        }

        lastOutrankGlobalByActor.put(actor, now);
        lastOutrankGlobalServerAt = now;
    }

    private int scoreAtRank(List<UUID> ordered, int rank) {
        if (rank < 1 || ordered.size() < rank) {
            return Integer.MIN_VALUE;
        }
        UUID uuid = ordered.get(rank - 1);
        return scores.getOrDefault(uuid, 0);
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000L);
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

}

