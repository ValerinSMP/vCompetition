package com.valerinsmp.vcompetition.service;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.BlockKey;
import com.valerinsmp.vcompetition.model.ChallengeType;
import com.valerinsmp.vcompetition.storage.SQLiteManager;
import com.valerinsmp.vcompetition.util.TimeUtil;
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

    // ── Slot IDs ─────────────────────────────────────────────────────────────
    public static final String SLOT_DAILY   = "daily";
    public static final String SLOT_SPECIAL = "special";

    // ── Per-slot encapsulated state ──────────────────────────────────────────
    private static final class ChallengeSlot {
        final String slotId;
        final ChallengeType type;
        final long start;
        volatile long end;
        volatile boolean scheduleManaged;

        final Map<UUID, Integer> scores           = new ConcurrentHashMap<>();
        final Map<UUID, Integer> dirtyScores      = new ConcurrentHashMap<>();
        final Map<UUID, List<UUID>> pendingOutrankBefore = new ConcurrentHashMap<>();
        final Map<UUID, Long> lastOutrankGlobalByActor   = new ConcurrentHashMap<>();
        final Map<UUID, Long> lastOutrankPrivateByVictim = new ConcurrentHashMap<>();
        volatile long lastOutrankGlobalServerAt;

        volatile BukkitTask endTask;
        volatile BukkitTask flushTask;
        volatile BukkitTask outrankTask;

        volatile List<UUID> rankSnapshot = List.of();
        volatile boolean rankingDirty = true;
        volatile long lastRankSnapshotRefreshAt;

        ChallengeSlot(String slotId, ChallengeType type, long start, long end, boolean scheduleManaged) {
            this.slotId = slotId;
            this.type = type;
            this.start = start;
            this.end = end;
            this.scheduleManaged = scheduleManaged;
        }
    }

    // ── Services & state ─────────────────────────────────────────────────────
    private final VCompetitionPlugin plugin;
    private final SQLiteManager sqliteManager;

    private volatile boolean runtimeActive;
    private final Map<String, ChallengeSlot> activeSlots = new ConcurrentHashMap<>();

    // Global player names (accumulated, never cleared per-challenge)
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    // Global win history (in-memory, persisted to DB separately)
    private final Map<UUID, Integer> winsTotal = new ConcurrentHashMap<>();
    private final Map<ChallengeType, Map<UUID, Integer>> winsByChallenge = new ConcurrentHashMap<>();

    // Global block-placement anti-exploit
    private final Set<BlockKey> placedBlocks   = ConcurrentHashMap.newKeySet();
    private final Set<BlockKey> dirtyPlacedAdd  = ConcurrentHashMap.newKeySet();
    private final Set<BlockKey> dirtyPlacedRemove = ConcurrentHashMap.newKeySet();
    private volatile BukkitTask placedFlushTask;

    // Natural entity tracking (anti-exploit)
    private final Set<UUID> naturalEntities = ConcurrentHashMap.newKeySet();

    // Config-loaded material / mob sets (volatile — reloaded without restart)
    private volatile Set<Material>   miningMaterials     = EnumSet.noneOf(Material.class);
    private volatile Set<Material>   woodcuttingMaterials = EnumSet.noneOf(Material.class);
    private volatile Set<Material>   fishingMaterials    = EnumSet.noneOf(Material.class);
    private volatile Set<Material>   farmingMaterials    = EnumSet.noneOf(Material.class);
    private volatile Set<Material>   otonoMaterials      = EnumSet.noneOf(Material.class);
    private volatile Set<EntityType> slayerMobs          = EnumSet.noneOf(EntityType.class);
    private volatile Set<String>     excludedWorlds      = Set.of();
    private final Map<String, Boolean> worldExclusionCache = new ConcurrentHashMap<>();

    // Outrank tuning (reloaded with config)
    private volatile long outrankGlobalCooldownMillis       = 15_000L;
    private volatile long outrankServerGlobalCooldownMillis = 8_000L;
    private volatile long outrankPrivateCooldownMillis      = 30_000L;
    private volatile int  outrankSummaryThreshold           = 3;
    private volatile int  outrankMaxPrivateVictimsPerEvent  = 2;
    private volatile int  outrankGlobalTopCutoff            = 3;
    private volatile long rankingRefreshIntervalMillis      = 200L;

    // ── Constructor ──────────────────────────────────────────────────────────
    public CompetitionService(VCompetitionPlugin plugin, SQLiteManager sqliteManager) {
        this.plugin = plugin;
        this.sqliteManager = sqliteManager;
    }

    // ── Runtime toggle ───────────────────────────────────────────────────────
    public void enableRuntime()  { runtimeActive = true; }
    public void disableRuntime() { runtimeActive = false; }
    public boolean isRuntimeActive() { return runtimeActive; }

    // ── Config reload ────────────────────────────────────────────────────────
    public void loadCompetitionRules() {
        miningMaterials      = loadMaterials("competition-types.mining.materials");
        woodcuttingMaterials = loadMaterials("competition-types.woodcutting.materials");
        fishingMaterials     = loadMaterials("competition-types.fishing.materials");
        farmingMaterials     = loadMaterials("competition-types.farming.materials");
        otonoMaterials       = loadMaterials("competition-types.otono.materials");
        slayerMobs           = loadEntityTypes("competition-types.slayer.mobs");

        excludedWorlds = plugin.getConfig().getStringList("excluded-worlds").stream()
                .map(v -> v == null ? "" : v.trim().toLowerCase(Locale.ROOT))
                .filter(v -> !v.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        worldExclusionCache.clear();

        outrankGlobalCooldownMillis       = Math.max(0L, plugin.getConfig().getLong("notifications.outrank.global-cooldown-ms", 15_000L));
        outrankServerGlobalCooldownMillis = Math.max(0L, plugin.getConfig().getLong("notifications.outrank.global-server-cooldown-ms", 8_000L));
        outrankPrivateCooldownMillis      = Math.max(0L, plugin.getConfig().getLong("notifications.outrank.private-cooldown-ms", 30_000L));
        outrankSummaryThreshold           = Math.max(1,  plugin.getConfig().getInt("notifications.outrank.summary-threshold", 3));
        outrankMaxPrivateVictimsPerEvent  = Math.max(0,  plugin.getConfig().getInt("notifications.outrank.max-private-victims-per-event", 2));
        outrankGlobalTopCutoff            = Math.max(1,  plugin.getConfig().getInt("notifications.outrank.global-top-cutoff", 3));
        rankingRefreshIntervalMillis      = Math.max(50L, plugin.getConfig().getLong("performance.ranking-refresh-ms", 200L));
    }

    // ── Persistent state ─────────────────────────────────────────────────────
    public void loadPersistentState() {
        try {
            placedBlocks.addAll(sqliteManager.loadPlacedBlocks().join());

            for (Map.Entry<UUID, SQLiteManager.PlayerWins> entry : sqliteManager.loadWinsTotal().join().entrySet()) {
                winsTotal.put(entry.getKey(), entry.getValue().wins());
                names.put(entry.getKey(), entry.getValue().playerName());
            }
            for (ChallengeType type : ChallengeType.values()) {
                Map<UUID, Integer> local = new ConcurrentHashMap<>();
                sqliteManager.loadWinsByChallenge(type).join().forEach((uuid, pw) -> {
                    local.put(uuid, pw.wins());
                    names.put(uuid, pw.playerName());
                });
                winsByChallenge.put(type, local);
            }

            for (String slotId : List.of(SLOT_DAILY, SLOT_SPECIAL)) {
                SQLiteManager.ChallengeStateSnapshot snap = sqliteManager.loadChallengeState(slotId).join();
                if (snap.active() && snap.challengeType() != null) {
                    ChallengeSlot slot = new ChallengeSlot(slotId, snap.challengeType(),
                            snap.startTime(), snap.endTime(), true);
                    sqliteManager.loadProgress(slotId, snap.challengeType()).join()
                            .forEach((uuid, ps) -> {
                                slot.scores.put(uuid, ps.points());
                                names.put(uuid, ps.playerName());
                            });
                    slot.rankingDirty = true;
                    refreshRankSnapshotNow(slot);
                    activeSlots.put(slotId, slot);
                    scheduleChallengeEnd(slot);
                }
            }
            // Purge placed-block history that survived from ended competitions.
            // If no block competition was restored, every BlockKey in the set is stale.
            if (!hasAnyBlockChallenge()) {
                placedBlocks.clear();
                sqliteManager.clearPlacedBlocks();
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("No se pudo restaurar estado: " + exception.getMessage());
        }
    }

    public void shutdownAndPersist() {
        List<CompletableFuture<Void>> pending = new ArrayList<>();

        for (ChallengeSlot slot : activeSlots.values()) {
            cancelSlotTasks(slot);
            flushDirtyScores(slot);
            pending.add(sqliteManager.saveChallengeState(slot.slotId, slot.type, slot.start, slot.end, true));
            pending.add(sqliteManager.batchUpsertPlayerScores(slot.slotId, slot.type, slot.scores, names));
        }
        if (activeSlots.isEmpty()) {
            pending.add(sqliteManager.saveChallengeState(SLOT_DAILY,   null, 0L, 0L, false));
            pending.add(sqliteManager.saveChallengeState(SLOT_SPECIAL, null, 0L, 0L, false));
        }

        if (placedFlushTask != null) {
            placedFlushTask.cancel();
            placedFlushTask = null;
        }
        flushDirtyPlaced();

        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).join();
        } catch (Exception exception) {
            plugin.getLogger().warning("Error durante guardado en onDisable: " + exception.getMessage());
        }

        naturalEntities.clear();
        placedBlocks.clear();
        activeSlots.clear();
    }

    // ── Slot query helpers ───────────────────────────────────────────────────
    public boolean hasActiveSlot(String slotId) {
        return activeSlots.containsKey(slotId);
    }

    public ChallengeType getActiveSlotType(String slotId) {
        ChallengeSlot slot = activeSlots.get(slotId);
        return slot == null ? null : slot.type;
    }

    public boolean isScheduleManagedSlot(String slotId) {
        ChallengeSlot slot = activeSlots.get(slotId);
        return slot != null && slot.scheduleManaged;
    }

    public long getRemainingMillis(String slotId) {
        ChallengeSlot slot = activeSlots.get(slotId);
        if (slot == null) return 0L;
        return Math.max(0L, slot.end - System.currentTimeMillis());
    }

    /** True if any active slot needs block-break events (mining/woodcutting/farming/otono). */
    public boolean hasAnyBlockChallenge() {
        for (ChallengeSlot slot : activeSlots.values()) {
            if (slot.type == ChallengeType.MINING
                    || slot.type == ChallengeType.WOODCUTTING
                    || slot.type == ChallengeType.FARMING
                    || slot.type == ChallengeType.OTONO) {
                return true;
            }
        }
        return false;
    }

    /** True if any active slot is running the given challenge type. */
    public boolean hasAnyChallengeOfType(ChallengeType type) {
        for (ChallengeSlot slot : activeSlots.values()) {
            if (slot.type == type) return true;
        }
        return false;
    }

    // ── Backward-compatible daily-slot accessors ─────────────────────────────
    public boolean hasActiveChallenge()              { return hasActiveSlot(SLOT_DAILY); }
    public ChallengeType getActiveChallenge()        { return getActiveSlotType(SLOT_DAILY); }
    public boolean isScheduleManagedChallengeActive(){ return isScheduleManagedSlot(SLOT_DAILY); }
    public long getRemainingMillis()                 { return getRemainingMillis(SLOT_DAILY); }

    // Special-event slot accessors
    public boolean hasActiveSpecialChallenge()              { return hasActiveSlot(SLOT_SPECIAL); }
    public ChallengeType getActiveSpecialChallenge()        { return getActiveSlotType(SLOT_SPECIAL); }
    public boolean isScheduleManagedSpecialChallengeActive(){ return isScheduleManagedSlot(SLOT_SPECIAL); }
    public long getSpecialRemainingMillis()                 { return getRemainingMillis(SLOT_SPECIAL); }

    // ── Player stats ─────────────────────────────────────────────────────────
    public int getPlayerPoints(String slotId, UUID uuid) {
        ChallengeSlot slot = activeSlots.get(slotId);
        return slot == null ? 0 : slot.scores.getOrDefault(uuid, 0);
    }
    public int getPlayerPoints(UUID uuid) { return getPlayerPoints(SLOT_DAILY, uuid); }

    public int getPlayerPosition(String slotId, UUID uuid) {
        ChallengeSlot slot = activeSlots.get(slotId);
        if (slot == null) return -1;
        refreshRankSnapshotIfNeeded(slot);
        int index = slot.rankSnapshot.indexOf(uuid);
        return index < 0 ? -1 : index + 1;
    }
    public int getPlayerPosition(UUID uuid) { return getPlayerPosition(SLOT_DAILY, uuid); }

    public int getGapTopOneToTwo(String slotId) {
        ChallengeSlot slot = activeSlots.get(slotId);
        if (slot == null) return 0;
        refreshRankSnapshotIfNeeded(slot);
        List<UUID> ordered = slot.rankSnapshot;
        if (ordered.size() < 2) return ordered.isEmpty() ? 0 : slot.scores.getOrDefault(ordered.get(0), 0);
        return slot.scores.getOrDefault(ordered.get(0), 0) - slot.scores.getOrDefault(ordered.get(1), 0);
    }
    public int getGapTopOneToTwo() { return getGapTopOneToTwo(SLOT_DAILY); }

    public int getGapToAbove(String slotId, UUID uuid) {
        ChallengeSlot slot = activeSlots.get(slotId);
        if (slot == null) return 0;
        refreshRankSnapshotIfNeeded(slot);
        int index = slot.rankSnapshot.indexOf(uuid);
        if (index <= 0) return 0;
        UUID above = slot.rankSnapshot.get(index - 1);
        return Math.max(0, slot.scores.getOrDefault(above, 0) - slot.scores.getOrDefault(uuid, 0));
    }
    public int getGapToAbove(UUID uuid) { return getGapToAbove(SLOT_DAILY, uuid); }

    public int getGapToBelow(String slotId, UUID uuid) {
        ChallengeSlot slot = activeSlots.get(slotId);
        if (slot == null) return 0;
        refreshRankSnapshotIfNeeded(slot);
        int index = slot.rankSnapshot.indexOf(uuid);
        if (index < 0 || index >= slot.rankSnapshot.size() - 1) return 0;
        UUID below = slot.rankSnapshot.get(index + 1);
        return Math.max(0, slot.scores.getOrDefault(uuid, 0) - slot.scores.getOrDefault(below, 0));
    }
    public int getGapToBelow(UUID uuid) { return getGapToBelow(SLOT_DAILY, uuid); }

    public int getWinsTotal(UUID uuid)                                     { return winsTotal.getOrDefault(uuid, 0); }
    public int getWinsByChallenge(UUID uuid, ChallengeType challengeType)  {
        return winsByChallenge.getOrDefault(challengeType, Collections.emptyMap()).getOrDefault(uuid, 0);
    }

    public VCompetitionPlugin.RankingEntry getCurrentTopAt(String slotId, int rank) {
        ChallengeSlot slot = activeSlots.get(slotId);
        if (slot == null) return null;
        refreshRankSnapshotIfNeeded(slot);
        List<UUID> ordered = slot.rankSnapshot;
        if (rank < 1 || rank > ordered.size()) return null;
        UUID uuid = ordered.get(rank - 1);
        return new VCompetitionPlugin.RankingEntry(uuid, names.getOrDefault(uuid, "Unknown"), slot.scores.getOrDefault(uuid, 0));
    }
    public VCompetitionPlugin.RankingEntry getCurrentTopAt(int rank) { return getCurrentTopAt(SLOT_DAILY, rank); }

    public VCompetitionPlugin.WinsEntry getGlobalWinsTopAt(int rank) {
        List<VCompetitionPlugin.WinsEntry> ranking = getGlobalWinsRanking();
        return (rank < 1 || rank > ranking.size()) ? null : ranking.get(rank - 1);
    }

    public VCompetitionPlugin.WinsEntry getChallengeWinsTopAt(ChallengeType type, int rank) {
        List<VCompetitionPlugin.WinsEntry> ranking = getChallengeWinsRanking(type);
        return (rank < 1 || rank > ranking.size()) ? null : ranking.get(rank - 1);
    }

    // ── Block placement anti-exploit ─────────────────────────────────────────
    public void registerPlacedBlock(BlockKey blockKey) {
        if (!runtimeActive) return;
        if (!hasAnyBlockChallenge()) return;
        if (placedBlocks.add(blockKey)) {
            dirtyPlacedAdd.add(blockKey);
            dirtyPlacedRemove.remove(blockKey);
            schedulePlacedFlush();
        }
    }

    public boolean consumeIfPlacedByPlayer(BlockKey blockKey) {
        if (!runtimeActive) return false;
        if (!placedBlocks.remove(blockKey)) return false;
        dirtyPlacedRemove.add(blockKey);
        dirtyPlacedAdd.remove(blockKey);
        schedulePlacedFlush();
        return true;
    }

    // ── Natural entity tracking ──────────────────────────────────────────────
    public void markNaturalEntity(Entity entity) {
        if (runtimeActive) naturalEntities.add(entity.getUniqueId());
    }

    public void unmarkNaturalEntity(Entity entity) {
        naturalEntities.remove(entity.getUniqueId());
    }

    public boolean isNaturalEntity(Entity entity) {
        if (!runtimeActive) return false;
        return naturalEntities.remove(entity.getUniqueId());
    }

    // ── Material / mob checks ────────────────────────────────────────────────
    public boolean isWorldExcluded(String worldName) {
        if (worldName == null || worldName.isBlank()) return false;
        return worldExclusionCache.computeIfAbsent(worldName,
                k -> excludedWorlds.contains(k.toLowerCase(Locale.ROOT)));
    }

    // ── Point addition (fans out to all matching active slots) ───────────────

    /** Called by the listener for block-break events. Adds to every active slot that cares about this material. */
    public void addBlockBreakPoints(Player player, Material material) {
        if (!runtimeActive || activeSlots.isEmpty()) return;
        if (isWorldExcluded(player.getWorld().getName())) return;
        UUID uuid = player.getUniqueId();
        for (ChallengeSlot slot : activeSlots.values()) {
            boolean matches = switch (slot.type) {
                case MINING      -> miningMaterials.contains(material);
                case WOODCUTTING -> woodcuttingMaterials.contains(material);
                case FARMING     -> farmingMaterials.contains(material);
                case OTONO       -> otonoMaterials.contains(material);
                default          -> false;
            };
            if (matches) {
                names.putIfAbsent(uuid, player.getName());
                addPointsToSlot(slot, player, uuid, 1);
            }
        }
    }

    /** Called by the listener for fish-catch events. */
    public void addFishPoints(Player player, Material material, int amount) {
        if (!runtimeActive || activeSlots.isEmpty()) return;
        if (isWorldExcluded(player.getWorld().getName())) return;
        UUID uuid = player.getUniqueId();
        for (ChallengeSlot slot : activeSlots.values()) {
            if (slot.type == ChallengeType.FISHING && fishingMaterials.contains(material)) {
                names.putIfAbsent(uuid, player.getName());
                addPointsToSlot(slot, player, uuid, amount);
            }
        }
    }

    /** Called by the listener for entity-kill events. */
    public void addKillPoints(Player player, EntityType entityType) {
        if (!runtimeActive || activeSlots.isEmpty()) return;
        if (isWorldExcluded(player.getWorld().getName())) return;
        UUID uuid = player.getUniqueId();
        for (ChallengeSlot slot : activeSlots.values()) {
            if (slot.type == ChallengeType.SLAYER && slayerMobs.contains(entityType)) {
                names.putIfAbsent(uuid, player.getName());
                addPointsToSlot(slot, player, uuid, 1);
            }
        }
    }

    private void addPointsToSlot(ChallengeSlot slot, Player player, UUID uuid, int amount) {
        if (amount <= 0) return;
        refreshRankSnapshotIfNeeded(slot);
        slot.pendingOutrankBefore.computeIfAbsent(uuid, k -> List.copyOf(slot.rankSnapshot));
        int updated = slot.scores.getOrDefault(uuid, 0) + amount;
        slot.scores.put(uuid, updated);
        markRankingDirty(slot);
        scheduleDirtyFlush(slot, uuid, player.getName(), updated);
        scheduleOutrankFlush(slot);
        if (plugin.getBossBarService() != null) {
            plugin.getBossBarService().showOrUpdate(slot.slotId, uuid);
        }
    }

    // ── Admin operations (daily slot) ────────────────────────────────────────
    public void startAdminChallenge(ChallengeType type) {
        startSlot(SLOT_DAILY, type, true, null, false);
    }

    public void startAdminChallengeUntilScheduleEnd(ChallengeType type, long forcedEndMillis) {
        startSlot(SLOT_DAILY, type, true, forcedEndMillis, true);
    }

    public void stopAdminChallenge() {
        stopSlot(SLOT_DAILY, true);
    }

    public void stopAdminChallengeNoRewards() {
        stopSlot(SLOT_DAILY, false);
    }

    // ── Admin operations (special slot) ─────────────────────────────────────
    public void startAdminSpecialChallenge(ChallengeType type) {
        startSlot(SLOT_SPECIAL, type, true, null, false);
    }

    public void startAdminSpecialChallengeUntilScheduleEnd(ChallengeType type, long forcedEndMillis) {
        startSlot(SLOT_SPECIAL, type, true, forcedEndMillis, true);
    }

    public void stopAdminSpecialChallenge() {
        stopSlot(SLOT_SPECIAL, true);
    }

    public void stopAdminSpecialChallengeNoRewards() {
        stopSlot(SLOT_SPECIAL, false);
    }

    // ── Schedule-managed operations ──────────────────────────────────────────
    public void startScheduledChallenge(String slotId, ChallengeType type, long slotEndMillis) {
        startSlot(slotId, type, true, slotEndMillis, true);
    }

    public void stopScheduledChallenge(String slotId) {
        stopSlot(slotId, true);
    }

    // ── Admin point editing ──────────────────────────────────────────────────
    public boolean handlePointEdit(CommandSender sender, String[] args, String label,
                                   VCompetitionPlugin.PointOperation operation, String slotId) {
        if (args.length < 3) {
            plugin.getMessageService().sendPath(sender, "messages.point-edit.usage",
                    List.of("&cUso: /%label% %operation% <jugador> <puntos>"),
                    plugin.getMessageService().placeholders("%label%", label, "%operation%", operation.commandName()));
            return true;
        }
        if (!hasActiveSlot(slotId)) {
            plugin.getMessageService().sendPath(sender, "messages.point-edit.no-active",
                    List.of("&cNo hay torneo activo."), Map.of());
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        int points;
        try {
            points = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            plugin.getMessageService().sendPath(sender, "messages.point-edit.invalid-points",
                    List.of("&cPuntos inválidos."), Map.of());
            return true;
        }

        UUID uuid = target.getUniqueId();
        if (uuid == null) {
            plugin.getMessageService().sendPath(sender, "messages.point-edit.invalid-player",
                    List.of("&cJugador inválido."), Map.of());
            return true;
        }
        String targetName = target.getName() == null ? args[1] : target.getName();

        ChallengeSlot slot = activeSlots.get(slotId);
        if (slot == null) return true;

        refreshRankSnapshotIfNeeded(slot);
        List<UUID> before = List.copyOf(slot.rankSnapshot);
        int base = slot.scores.getOrDefault(uuid, 0);
        int safePoints = Math.max(0, points);
        int updated = switch (operation) {
            case SET    -> safePoints;
            case ADD    -> base + safePoints;
            case REMOVE -> Math.max(0, base - safePoints);
        };

        slot.scores.put(uuid, updated);
        names.put(uuid, targetName);
        markRankingDirty(slot);
        refreshRankSnapshotNow(slot);
        scheduleDirtyFlush(slot, uuid, targetName, updated);
        List<UUID> after = List.copyOf(slot.rankSnapshot);
        notifyOutranks(slot, uuid, before, after);

        plugin.getMessageService().sendPath(sender, "messages.point-edit.updated",
                List.of("&aPuntaje actualizado: &e%player% &7-> &b%points%"),
                plugin.getMessageService().placeholders("%player%", targetName, "%points%", String.valueOf(updated)));
        return true;
    }

    /** Backward-compat variant — targets the daily slot. */
    public boolean handlePointEdit(CommandSender sender, String[] args, String label,
                                   VCompetitionPlugin.PointOperation operation) {
        return handlePointEdit(sender, args, label, operation, SLOT_DAILY);
    }

    // ── Misc admin utilities ─────────────────────────────────────────────────
    public void updateDurationMinutes(long minutes) {
        plugin.getConfig().set("challenge.duration-minutes", minutes);
        plugin.saveConfig();

        ChallengeSlot slot = activeSlots.get(SLOT_DAILY);
        if (slot != null) {
            slot.end = slot.start + (minutes * 60L * 1_000L);
            scheduleChallengeEnd(slot);
            sqliteManager.saveChallengeState(SLOT_DAILY, slot.type, slot.start, slot.end, true);
        }
    }

    public void resetPlacedCache() {
        if (placedFlushTask != null) {
            placedFlushTask.cancel();
            placedFlushTask = null;
        }
        dirtyPlacedAdd.clear();
        dirtyPlacedRemove.clear();
        placedBlocks.clear();
        sqliteManager.clearPlacedBlocks();
    }

    // ── Display helpers ──────────────────────────────────────────────────────
    public Component statusLine() {
        if (activeSlots.isEmpty()) {
            String line = plugin.getMessageService()
                    .getLines("messages.status-no-active", List.of("&cNo hay torneo activo."))
                    .stream().findFirst().orElse("&cNo hay torneo activo.");
            return plugin.getMessageService().renderPrefixed(line);
        }
        // Build one status line per active slot
        net.kyori.adventure.text.TextComponent.Builder builder = net.kyori.adventure.text.Component.text();
        boolean first = true;
        for (ChallengeSlot slot : activeSlots.values()) {
            if (!first) builder.append(net.kyori.adventure.text.Component.newline());
            String msg = plugin.getMessageService()
                    .getLines("messages.status-active", List.of("&aTorneo activo: %challenge% | %time_left%"))
                    .stream().findFirst().orElse("&aTorneo activo: %challenge% | %time_left%");
            msg = plugin.getMessageService().applyPlaceholders(msg,
                    plugin.getMessageService().placeholders(
                            "%challenge%", plugin.getMessageService().challengeDisplayName(slot.type),
                            "%time_left%", formatDuration(getRemainingMillis(slot.slotId))));
            builder.append(plugin.getMessageService().renderPrefixed(msg));
            first = false;
        }
        return builder.build();
    }

    public void sendUnifiedTop(CommandSender sender) {
        boolean hasDaily = activeSlots.containsKey(SLOT_DAILY);
        boolean hasSpecial = activeSlots.containsKey(SLOT_SPECIAL);
        if (!hasDaily && !hasSpecial) {
            plugin.getMessageService().sendPath(sender, "messages.status-no-active",
                    List.of("<gray>No hay torneo activo."), Map.of());
            return;
        }
        if (hasDaily) sendTop(sender, SLOT_DAILY);
        if (hasSpecial) sendTop(sender, SLOT_SPECIAL);
    }

    public void sendTop(CommandSender sender, String slotId) {
        ChallengeSlot slot = activeSlots.get(slotId);
        String challengeName = slot != null ? plugin.getMessageService().challengeDisplayName(slot.type) : slotId;
        plugin.getMessageService().sendPath(sender, "messages.top-header", List.of("&7Top"),
                plugin.getMessageService().placeholders("%challenge%", challengeName));
        List<VCompetitionPlugin.RankingEntry> top = getRankingEntries(slotId);
        if (top.isEmpty()) {
            plugin.getMessageService().sendPath(sender, "messages.top-empty", List.of("&7Sin participantes"), Map.of());
            return;
        }
        int max = Math.min(10, top.size());
        for (int i = 0; i < max; i++) {
            VCompetitionPlugin.RankingEntry entry = top.get(i);
            plugin.getMessageService().sendPath(sender, "messages.top-line", List.of("#%rank% %player% %points%"),
                    plugin.getMessageService().placeholders(
                            "%rank%", String.valueOf(i + 1),
                            "%player%", entry.name(),
                            "%points%", String.valueOf(entry.points())));
        }
    }

    public void sendDebug(CommandSender sender, boolean papiRegistered) {
        plugin.getMessageService().sendPath(sender, "messages.debug.header", List.of("&7------ &bvCompetition Debug &7------"), Map.of());
        plugin.getMessageService().sendPath(sender, "messages.debug.db-connected",
                List.of("&fDB conectada: &a%value%"),
                plugin.getMessageService().placeholders("%value%", String.valueOf(sqliteManager.isConnected())));
        plugin.getMessageService().sendPath(sender, "messages.debug.db-active-tasks",
                List.of("&fDB tareas activas: &e%value%"),
                plugin.getMessageService().placeholders("%value%", String.valueOf(sqliteManager.getActiveTasks())));
        plugin.getMessageService().sendPath(sender, "messages.debug.db-queue-size",
                List.of("&fDB cola pendiente: &e%value%"),
                plugin.getMessageService().placeholders("%value%", String.valueOf(sqliteManager.getQueueSize())));
        plugin.getMessageService().sendPath(sender, "messages.debug.db-pending-futures",
                List.of("&fDB futures pendientes: &e%value%"),
                plugin.getMessageService().placeholders("%value%", String.valueOf(sqliteManager.getPendingTasks())));

        String dailyInfo  = hasActiveChallenge()        ? getActiveChallenge().name()        : "none";
        String specialInfo = hasActiveSpecialChallenge() ? getActiveSpecialChallenge().name() : "none";
        plugin.getMessageService().sendPath(sender, "messages.debug.challenge-active",
                List.of("&fTorneo diario: &a%value%"),
                plugin.getMessageService().placeholders("%value%", dailyInfo));
        plugin.getMessageService().sendPath(sender, "messages.debug.challenge-active-special",
                List.of("&fEvento especial: &a%value%"),
                plugin.getMessageService().placeholders("%value%", specialInfo));

        int totalParticipants = activeSlots.values().stream().mapToInt(s -> s.scores.size()).sum();
        plugin.getMessageService().sendPath(sender, "messages.debug.participants",
                List.of("&fParticipantes totales cargados: &b%value%"),
                plugin.getMessageService().placeholders("%value%", String.valueOf(totalParticipants)));
        plugin.getMessageService().sendPath(sender, "messages.debug.placed-cache",
                List.of("&fPlaced blocks cache: &d%value%"),
                plugin.getMessageService().placeholders("%value%", String.valueOf(placedBlocks.size())));
        plugin.getMessageService().sendPath(sender, "messages.debug.papi-registered",
                List.of("&fPAPI registrado: &6%value%"),
                plugin.getMessageService().placeholders("%value%", String.valueOf(papiRegistered)));

        boolean dailyEndActive   = activeSlots.containsKey(SLOT_DAILY)   && activeSlots.get(SLOT_DAILY).endTask   != null;
        boolean specialEndActive = activeSlots.containsKey(SLOT_SPECIAL) && activeSlots.get(SLOT_SPECIAL).endTask != null;
        plugin.getMessageService().sendPath(sender, "messages.debug.end-task-active",
                List.of("&fTask fin diario/especial: &6%value%"),
                plugin.getMessageService().placeholders("%value%", dailyEndActive + " / " + specialEndActive));
        plugin.getMessageService().sendPath(sender, "messages.debug.schedule-managed",
                List.of("&fSchedule managed (d/s): &b%value%"),
                plugin.getMessageService().placeholders("%value%", isScheduleManagedChallengeActive() + " / " + isScheduleManagedSpecialChallengeActive()));
    }

    // ── Ranking helpers ──────────────────────────────────────────────────────
    public List<VCompetitionPlugin.RankingEntry> getRankingEntries(String slotId) {
        ChallengeSlot slot = activeSlots.get(slotId);
        if (slot == null) return List.of();
        refreshRankSnapshotIfNeeded(slot);
        List<VCompetitionPlugin.RankingEntry> entries = new ArrayList<>();
        for (UUID uuid : slot.rankSnapshot) {
            entries.add(new VCompetitionPlugin.RankingEntry(uuid,
                    names.getOrDefault(uuid, "Unknown"),
                    slot.scores.getOrDefault(uuid, 0)));
        }
        return entries;
    }

    public List<VCompetitionPlugin.RankingEntry> getRankingEntries() {
        return getRankingEntries(SLOT_DAILY);
    }

    private List<VCompetitionPlugin.WinsEntry> getGlobalWinsRanking() {
        List<VCompetitionPlugin.WinsEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : winsTotal.entrySet()) {
            UUID uuid = entry.getKey();
            entries.add(new VCompetitionPlugin.WinsEntry(uuid, names.getOrDefault(uuid, "Unknown"), entry.getValue()));
        }
        entries.sort(Comparator.comparingInt(VCompetitionPlugin.WinsEntry::wins).reversed()
                .thenComparing(VCompetitionPlugin.WinsEntry::name));
        return entries;
    }

    private List<VCompetitionPlugin.WinsEntry> getChallengeWinsRanking(ChallengeType type) {
        Map<UUID, Integer> map = winsByChallenge.getOrDefault(type, Collections.emptyMap());
        List<VCompetitionPlugin.WinsEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : map.entrySet()) {
            UUID uuid = entry.getKey();
            entries.add(new VCompetitionPlugin.WinsEntry(uuid, names.getOrDefault(uuid, "Unknown"), entry.getValue()));
        }
        entries.sort(Comparator.comparingInt(VCompetitionPlugin.WinsEntry::wins).reversed()
                .thenComparing(VCompetitionPlugin.WinsEntry::name));
        return entries;
    }

    // ── Internal slot lifecycle ───────────────────────────────────────────────
    private void startSlot(String slotId, ChallengeType type, boolean announce,
                           Long forcedEndMillis, boolean scheduleManaged) {
        // Stop existing challenge in this slot without rewards
        if (activeSlots.containsKey(slotId)) {
            stopSlot(slotId, false);
        }

        long start = System.currentTimeMillis();
        long defaultEnd = start + (plugin.getConfig().getLong("challenge.duration-minutes", 30L) * 60L * 1_000L);
        long end = (forcedEndMillis != null && forcedEndMillis > start) ? forcedEndMillis : defaultEnd;

        ChallengeSlot slot = new ChallengeSlot(slotId, type, start, end, scheduleManaged);
        sqliteManager.clearProgress(slotId, type);
        sqliteManager.saveChallengeState(slotId, type, start, end, true);

        activeSlots.put(slotId, slot);
        scheduleChallengeEnd(slot);

        if (announce) {
            String description = plugin.getMessageService().getString("messages.info.descriptions." + type.name(), "");
            plugin.getMessageService().broadcastPath("messages.challenge-start",
                    List.of("<green>Inició %challenge%</green>"),
                    plugin.getMessageService().placeholders(
                            "%challenge%", plugin.getMessageService().challengeDisplayName(type),
                            "%description%", description,
                            "%type_raw%", type.name().toLowerCase(Locale.ROOT)));
            if (plugin.getSoundService() != null) {
                plugin.getSoundService().playTournamentStart();
            }
        }
    }

    private void stopSlot(String slotId, boolean announceAndReward) {
        ChallengeSlot slot = activeSlots.remove(slotId);
        if (slot == null) return;

        // Cancel tasks but keep scores intact so the ranking can be computed
        cancelBukkitTasks(slot);
        flushDirtyScores(slot);

        if (announceAndReward) {
            plugin.getMessageService().broadcastPath("messages.challenge-end",
                    List.of("<red>Terminó %challenge%</red>"),
                    plugin.getMessageService().placeholders("%challenge%", plugin.getMessageService().challengeDisplayName(slot.type)));
            if (plugin.getSoundService() != null) {
                plugin.getSoundService().playTournamentEnd();
            }
        }

        // Build ranking BEFORE clearing scores
        List<VCompetitionPlugin.RankingEntry> ranking = getRankingEntriesFromSlot(slot);
        if (announceAndReward) {
            applyRewardsAndAnnounceWinners(slot, ranking);
        }

        // Clear all in-memory state now that rewards are processed
        clearSlotState(slot);

        if (plugin.getBossBarService() != null) {
            plugin.getBossBarService().hideSlotBars(slotId);
        }
        sqliteManager.saveChallengeState(slotId, null, 0L, 0L, false);

        // Placed-block anti-exploit data is only relevant while a block competition is active.
        // Clear it when no block challenges remain so the set doesn't grow unboundedly.
        if (!hasAnyBlockChallenge()) {
            resetPlacedCache();
        }
    }

    private List<VCompetitionPlugin.RankingEntry> getRankingEntriesFromSlot(ChallengeSlot slot) {
        refreshRankSnapshotNow(slot);
        List<VCompetitionPlugin.RankingEntry> entries = new ArrayList<>();
        for (UUID uuid : slot.rankSnapshot) {
            entries.add(new VCompetitionPlugin.RankingEntry(uuid,
                    names.getOrDefault(uuid, "Unknown"),
                    slot.scores.getOrDefault(uuid, 0)));
        }
        return entries;
    }

    private void applyRewardsAndAnnounceWinners(ChallengeSlot slot,
                                                 List<VCompetitionPlugin.RankingEntry> ranking) {
        ChallengeType challengeType = slot.type;

        // Top 1/2/3 rewards
        int limit = Math.min(3, ranking.size());
        for (int i = 0; i < limit; i++) {
            VCompetitionPlugin.RankingEntry entry = ranking.get(i);
            List<String> commands = getChallengeRewardCommands(challengeType, "top" + (i + 1));
            for (String cmd : commands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", entry.name()));
            }
        }

        // Participation reward (all ranked players)
        List<String> participantCommands = getChallengeRewardCommands(challengeType, "participant");
        if (!participantCommands.isEmpty()) {
            for (VCompetitionPlugin.RankingEntry entry : ranking) {
                for (String cmd : participantCommands) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", entry.name()));
                }
            }
        }

        if (ranking.isEmpty()) {
            plugin.getMessageService().broadcastPath("messages.challenge-end-no-participants",
                    List.of("%prefix%<gray>No hubo participantes en este torneo."), Map.of());
        } else {
            VCompetitionPlugin.RankingEntry winner = ranking.get(0);
            long now = System.currentTimeMillis();

            winsTotal.put(winner.uuid(), winsTotal.getOrDefault(winner.uuid(), 0) + 1);
            winsByChallenge.computeIfAbsent(challengeType, unused -> new ConcurrentHashMap<>())
                    .merge(winner.uuid(), 1, Integer::sum);
            names.put(winner.uuid(), winner.name());
            sqliteManager.recordWinner(challengeType, winner.uuid(), winner.name(), winner.points(), now);

            int topLimit = Math.min(3, ranking.size());
            for (int i = 0; i < topLimit; i++) {
                VCompetitionPlugin.RankingEntry entry = ranking.get(i);
                String rewardDisplay = getChallengeRewardDisplay(challengeType, "top" + (i + 1));
                plugin.getMessageService().broadcastPath("messages.challenge-end-top" + (i + 1),
                        List.of("%prefix%<gray>#" + (i + 1) + " <yellow>%player%</yellow> — <aqua>%points% pts</aqua>"),
                        plugin.getMessageService().placeholders(
                                "%player%", entry.name(),
                                "%points%", String.valueOf(entry.points()),
                                "%reward%", rewardDisplay));
            }

            Player onlineWinner = Bukkit.getPlayer(winner.uuid());
            if (onlineWinner != null) {
                plugin.getMessageService().sendPath(onlineWinner, "messages.winner-private",
                        List.of("<green>Ganaste</green>"),
                        plugin.getMessageService().placeholders(
                                "%challenge%", plugin.getMessageService().challengeDisplayName(challengeType),
                                "%player%", winner.name(),
                                "%points%", String.valueOf(winner.points())));
            }
        }
    }

    /** Resolves reward commands for a challenge type and position, falling back to global rewards. */
    private List<String> getChallengeRewardCommands(ChallengeType type, String position) {
        String typePath = "competition-types." + type.name().toLowerCase(Locale.ROOT)
                + ".rewards." + position + ".commands";
        List<String> specific = plugin.getConfig().getStringList(typePath);
        if (!specific.isEmpty()) return specific;
        return plugin.getConfig().getStringList("rewards." + position + ".commands");
    }

    /** Resolves the human-readable reward description for a challenge type and position. */
    private String getChallengeRewardDisplay(ChallengeType type, String position) {
        String typePath = "competition-types." + type.name().toLowerCase(Locale.ROOT)
                + ".rewards." + position + ".display";
        String specific = plugin.getConfig().getString(typePath);
        if (specific != null && !specific.isBlank()) return specific;
        String global = plugin.getConfig().getString("rewards." + position + ".display", "");
        return global != null ? global : "";
    }

    // ── Per-slot task scheduling helpers ────────────────────────────────────

    /** Cancels all Bukkit tasks for this slot. Does NOT touch scores or state. */
    private void cancelBukkitTasks(ChallengeSlot slot) {
        if (slot.outrankTask != null) { slot.outrankTask.cancel(); slot.outrankTask = null; }
        if (slot.flushTask   != null) { slot.flushTask.cancel();   slot.flushTask   = null; }
        if (slot.endTask     != null) { slot.endTask.cancel();     slot.endTask     = null; }
    }

    /** Clears all in-memory state for this slot. Call AFTER ranking/rewards are processed. */
    private void clearSlotState(ChallengeSlot slot) {
        slot.pendingOutrankBefore.clear();
        slot.rankSnapshot = List.of();
        slot.rankingDirty = true;
        slot.dirtyScores.clear();
        slot.scores.clear();
        slot.lastOutrankGlobalByActor.clear();
        slot.lastOutrankPrivateByVictim.clear();
        slot.lastOutrankGlobalServerAt = 0L;
    }

    /** Used only in shutdownAndPersist — cancels tasks and clears state together. */
    private void cancelSlotTasks(ChallengeSlot slot) {
        cancelBukkitTasks(slot);
        clearSlotState(slot);
    }

    private void scheduleChallengeEnd(ChallengeSlot slot) {
        if (slot.endTask != null) slot.endTask.cancel();
        long remainingMillis = slot.end - System.currentTimeMillis();
        long ticks = Math.max(20L, remainingMillis / 50L);
        slot.endTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> stopSlot(slot.slotId, true), ticks);
    }

    private void scheduleDirtyFlush(ChallengeSlot slot, UUID uuid, String name, int points) {
        slot.dirtyScores.put(uuid, points);
        if (slot.flushTask != null) return;
        // Coalesce DB writes — flush after ~200ms of inactivity
        slot.flushTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            slot.flushTask = null;
            flushDirtyScores(slot);
        }, 4L);
    }

    private void flushDirtyScores(ChallengeSlot slot) {
        if (slot.dirtyScores.isEmpty()) return;
        Map<UUID, Integer> snapshot = new java.util.HashMap<>(slot.dirtyScores);
        // Use conditional remove to avoid silently discarding concurrent score updates:
        // if the value changed since we snapshotted it, leave it in dirty for the next flush.
        snapshot.forEach((uuid, pts) -> slot.dirtyScores.remove(uuid, pts));
        sqliteManager.batchUpsertPlayerScores(slot.slotId, slot.type, snapshot, names);
    }

    private void scheduleOutrankFlush(ChallengeSlot slot) {
        if (slot.outrankTask != null) return;
        slot.outrankTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            slot.outrankTask = null;
            if (slot.pendingOutrankBefore.isEmpty()) return;
            Map<UUID, List<UUID>> batch = new java.util.HashMap<>(slot.pendingOutrankBefore);
            slot.pendingOutrankBefore.keySet().removeAll(batch.keySet());
            refreshRankSnapshotNow(slot);
            List<UUID> after = List.copyOf(slot.rankSnapshot);
            for (Map.Entry<UUID, List<UUID>> entry : batch.entrySet()) {
                notifyOutranks(slot, entry.getKey(), entry.getValue(), after);
            }
        }, 2L);
    }

    private void schedulePlacedFlush() {
        if (placedFlushTask != null) return;
        placedFlushTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            placedFlushTask = null;
            flushDirtyPlaced();
        }, 4L);
    }

    private void flushDirtyPlaced() {
        Set<BlockKey> toAdd = new java.util.HashSet<>(dirtyPlacedAdd);
        dirtyPlacedAdd.removeAll(toAdd);
        Set<BlockKey> toRemove = new java.util.HashSet<>(dirtyPlacedRemove);
        dirtyPlacedRemove.removeAll(toRemove);
        if (!toAdd.isEmpty())    sqliteManager.batchAddPlacedBlocks(toAdd);
        if (!toRemove.isEmpty()) sqliteManager.batchRemovePlacedBlocks(toRemove);
    }

    // ── Ranking snapshot helpers ─────────────────────────────────────────────
    private void markRankingDirty(ChallengeSlot slot) { slot.rankingDirty = true; }

    private void refreshRankSnapshotNow(ChallengeSlot slot) {
        slot.rankSnapshot = rankOrdered(slot);
        slot.rankingDirty = false;
        slot.lastRankSnapshotRefreshAt = System.currentTimeMillis();
    }

    private void refreshRankSnapshotIfNeeded(ChallengeSlot slot) {
        if (!slot.rankingDirty) return;
        long now = System.currentTimeMillis();
        if (now - slot.lastRankSnapshotRefreshAt < rankingRefreshIntervalMillis) return;
        refreshRankSnapshotNow(slot);
    }

    private List<UUID> rankOrdered(ChallengeSlot slot) {
        List<UUID> ordered = new ArrayList<>(slot.scores.keySet());
        ordered.sort((a, b) -> {
            int cmp = Integer.compare(slot.scores.getOrDefault(b, 0), slot.scores.getOrDefault(a, 0));
            if (cmp != 0) return cmp;
            return names.getOrDefault(a, "").compareToIgnoreCase(names.getOrDefault(b, ""));
        });
        return ordered;
    }

    // ── Outrank notifications ────────────────────────────────────────────────
    private void notifyOutranks(ChallengeSlot slot, UUID actor, List<UUID> before, List<UUID> after) {
        int oldIndex = before.indexOf(actor);
        int newIndex = after.indexOf(actor);
        if (newIndex < 0 || oldIndex < 0 || newIndex >= oldIndex) return;

        String rankText  = String.valueOf(newIndex + 1);
        Set<UUID> victims = new HashSet<>(before.subList(newIndex, oldIndex));
        victims.remove(actor);
        if (victims.isEmpty()) return;

        String actorName   = names.getOrDefault(actor, "Unknown");
        long   now         = System.currentTimeMillis();
        int    actorPoints = slot.scores.getOrDefault(actor, 0);
        int    prevCutoff  = scoreAtRank(slot, before, outrankGlobalTopCutoff);
        Player actorPlayer = Bukkit.getPlayer(actor);

        if (newIndex + 1 > outrankGlobalTopCutoff) {
            sendSelfOutrankMessage(actorPlayer, slot, actorName, newIndex, victims.size());
            return;
        }

        if (prevCutoff != Integer.MIN_VALUE && actorPoints <= prevCutoff) {
            sendSelfOutrankMessage(actorPlayer, slot, actorName, newIndex, victims.size());
            return;
        }

        int privateSent = 0;
        for (UUID victimUuid : victims) {
            if (privateSent >= outrankMaxPrivateVictimsPerEvent) break;
            Player victim = Bukkit.getPlayer(victimUuid);
            if (victim != null && victim.isOnline()) {
                if (now - slot.lastOutrankPrivateByVictim.getOrDefault(victimUuid, 0L) < outrankPrivateCooldownMillis) continue;
                plugin.getMessageService().sendPath(victim, "messages.outrank-private",
                        List.of("<yellow>Superado por %player%</yellow>"),
                        plugin.getMessageService().placeholders(
                                "%challenge%", plugin.getMessageService().challengeDisplayName(slot.type),
                                "%player%", actorName,
                                "%victim%", names.getOrDefault(victimUuid, "Unknown"),
                                "%rank%", rankText));
                slot.lastOutrankPrivateByVictim.put(victimUuid, now);
                privateSent++;
            }
        }

        if (now - slot.lastOutrankGlobalByActor.getOrDefault(actor, 0L) < outrankGlobalCooldownMillis) return;
        if (now - slot.lastOutrankGlobalServerAt < outrankServerGlobalCooldownMillis) return;

        if (victims.size() >= outrankSummaryThreshold) {
            plugin.getMessageService().broadcastPath("messages.outrank-global-summary",
                    List.of("%prefix%<aqua>%player%</aqua> <gray>subió al puesto <yellow>#%rank%</yellow> superando a <yellow>%count%</yellow> jugadores.</gray>"),
                    plugin.getMessageService().placeholders(
                            "%challenge%", plugin.getMessageService().challengeDisplayName(slot.type),
                            "%player%", actorName,
                            "%rank%", String.valueOf(newIndex + 1),
                            "%count%", String.valueOf(victims.size())));
        } else {
            for (UUID victimUuid : victims) {
                plugin.getMessageService().broadcastPath("messages.outrank-global",
                        List.of("<aqua>%player% superó a %victim%</aqua>"),
                        plugin.getMessageService().placeholders(
                                "%challenge%", plugin.getMessageService().challengeDisplayName(slot.type),
                                "%player%", actorName,
                                "%victim%", names.getOrDefault(victimUuid, "Unknown"),
                                "%rank%", rankText));
            }
        }

        if (plugin.getSoundService() != null && actorPlayer != null) {
            plugin.getSoundService().playOutrank(actorPlayer, null);
        }
        slot.lastOutrankGlobalByActor.put(actor, now);
        slot.lastOutrankGlobalServerAt = now;
    }

    private void sendSelfOutrankMessage(Player actorPlayer, ChallengeSlot slot,
                                         String actorName, int newIndex, int victimCount) {
        if (actorPlayer == null || !actorPlayer.isOnline()) return;
        plugin.getMessageService().sendPath(actorPlayer, "messages.outrank-self",
                List.of("%prefix%<gray>Subiste al puesto <yellow>#%rank%</yellow> superando a <yellow>%count%</yellow> jugadores.</gray>"),
                plugin.getMessageService().placeholders(
                        "%challenge%", plugin.getMessageService().challengeDisplayName(slot.type),
                        "%player%", actorName,
                        "%rank%", String.valueOf(newIndex + 1),
                        "%count%", String.valueOf(victimCount)));
        if (plugin.getSoundService() != null) {
            plugin.getSoundService().playOutrank(actorPlayer, null);
        }
    }

    private int scoreAtRank(ChallengeSlot slot, List<UUID> ordered, int rank) {
        if (rank < 1 || ordered.size() < rank) return Integer.MIN_VALUE;
        return slot.scores.getOrDefault(ordered.get(rank - 1), 0);
    }

    // ── Config loaders ───────────────────────────────────────────────────────
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

    // ── Utility ──────────────────────────────────────────────────────────────
    private String formatDuration(long millis) {
        return TimeUtil.formatDuration(millis);
    }
}
