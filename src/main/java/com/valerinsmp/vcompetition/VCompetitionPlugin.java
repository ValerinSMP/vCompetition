package com.valerinsmp.vcompetition;

import com.valerinsmp.vcompetition.command.VCompetitionAdminCommand;
import com.valerinsmp.vcompetition.command.VCompetitionCommand;
import com.valerinsmp.vcompetition.listener.CompetitionListener;
import com.valerinsmp.vcompetition.model.BlockKey;
import com.valerinsmp.vcompetition.model.ChallengeType;
import com.valerinsmp.vcompetition.placeholder.VCompetitionPlaceholderExpansion;
import com.valerinsmp.vcompetition.service.BossBarService;
import com.valerinsmp.vcompetition.service.CompetitionService;
import com.valerinsmp.vcompetition.service.DailyScheduleService;
import com.valerinsmp.vcompetition.service.FancyNpcSkinRefreshService;
import com.valerinsmp.vcompetition.service.MessageService;
import com.valerinsmp.vcompetition.service.SoundService;
import com.valerinsmp.vcompetition.storage.SQLiteManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.UUID;

public final class VCompetitionPlugin extends JavaPlugin {
    private SQLiteManager sqliteManager;
    private MessageService messageService;
    private SoundService soundService;
    private CompetitionService competitionService;
    private DailyScheduleService dailyScheduleService;
    private DailyScheduleService specialEventScheduleService;
    private FancyNpcSkinRefreshService fancyNpcSkinRefreshService;
    private BossBarService bossBarService;
    private VCompetitionPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("sounds.yml");

        sqliteManager  = new SQLiteManager(this);
        messageService = new MessageService(this);
        soundService   = new SoundService(this);
        try {
            sqliteManager.connect();
        } catch (SQLException exception) {
            getLogger().severe("No se pudo inicializar SQLite: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        competitionService = new CompetitionService(this, sqliteManager);
        competitionService.loadCompetitionRules();
        competitionService.enableRuntime();
        competitionService.loadPersistentState();

        getServer().getPluginManager().registerEvents(new CompetitionListener(this), this);

        VCompetitionCommand userCommand = new VCompetitionCommand(this);
        if (getCommand("vcompetition") != null) {
            getCommand("vcompetition").setExecutor(userCommand);
            getCommand("vcompetition").setTabCompleter(userCommand);
        }

        VCompetitionAdminCommand adminCommand = new VCompetitionAdminCommand(this);
        if (getCommand("vcompetitionadmin") != null) {
            getCommand("vcompetitionadmin").setExecutor(adminCommand);
            getCommand("vcompetitionadmin").setTabCompleter(adminCommand);
        }

        dailyScheduleService        = new DailyScheduleService(this, CompetitionService.SLOT_DAILY,   "schedule");
        specialEventScheduleService = new DailyScheduleService(this, CompetitionService.SLOT_SPECIAL, "special-event");
        dailyScheduleService.start();
        specialEventScheduleService.start();

        fancyNpcSkinRefreshService = new FancyNpcSkinRefreshService(this);
        fancyNpcSkinRefreshService.start();

        bossBarService = new BossBarService(this);
        bossBarService.start();

        registerPlaceholders();
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (dailyScheduleService != null) {
            dailyScheduleService.stop();
            dailyScheduleService = null;
        }
        if (specialEventScheduleService != null) {
            specialEventScheduleService.stop();
            specialEventScheduleService = null;
        }
        if (fancyNpcSkinRefreshService != null) {
            fancyNpcSkinRefreshService.stop();
            fancyNpcSkinRefreshService = null;
        }
        if (bossBarService != null) {
            bossBarService.stop();
            bossBarService = null;
        }
        if (competitionService != null) {
            competitionService.disableRuntime();
            competitionService.shutdownAndPersist();
        }
        if (sqliteManager != null) {
            boolean drained = sqliteManager.awaitPendingTasks(7000L);
            if (!drained) {
                getLogger().warning("No se vació completamente la cola async antes del shutdown.");
            }
            sqliteManager.shutdown();
            sqliteManager = null;
        }
        messageService = null;
        soundService   = null;
    }

    // ── Config / services ────────────────────────────────────────────────────
    public void reloadPluginRuntime() {
        reloadConfig();
        if (messageService != null) messageService.reload();
        if (soundService   != null) soundService.reload();
        if (competitionService != null) competitionService.loadCompetitionRules();
        if (dailyScheduleService != null) dailyScheduleService.start();
        if (specialEventScheduleService != null) specialEventScheduleService.start();
        if (fancyNpcSkinRefreshService != null) fancyNpcSkinRefreshService.start();
        if (bossBarService != null) bossBarService.start();
    }

    public MessageService  getMessageService()  { return messageService;  }
    public SoundService    getSoundService()    { return soundService;    }
    public BossBarService  getBossBarService()  { return bossBarService;  }

    // Slot-aware point/position proxies used by BossBarService
    public int getPlayerPoints(String slotId, UUID uuid)   { return competitionService == null ? 0  : competitionService.getPlayerPoints(slotId, uuid); }
    public int getPlayerPosition(String slotId, UUID uuid) { return competitionService == null ? -1 : competitionService.getPlayerPosition(slotId, uuid); }
    public VCompetitionPlugin.RankingEntry getCurrentTopAt(String slotId, int rank) { return competitionService == null ? null : competitionService.getCurrentTopAt(slotId, rank); }

    // ── Slot-aware competition queries ────────────────────────────────────────
    public boolean hasActiveChallenge(String slotId) {
        return competitionService != null && competitionService.hasActiveSlot(slotId);
    }
    public boolean isScheduleManagedChallengeActive(String slotId) {
        return competitionService != null && competitionService.isScheduleManagedSlot(slotId);
    }

    // ── Daily-slot proxies (backward-compat) ──────────────────────────────────
    public boolean isRuntimeActive()          { return competitionService != null && competitionService.isRuntimeActive(); }
    public boolean hasActiveChallenge()       { return competitionService != null && competitionService.hasActiveChallenge(); }
    public boolean isScheduleManagedChallengeActive() { return competitionService != null && competitionService.isScheduleManagedChallengeActive(); }
    public ChallengeType getActiveChallenge() { return competitionService == null ? null : competitionService.getActiveChallenge(); }
    public long getRemainingMillis()          { return competitionService == null ? 0L  : competitionService.getRemainingMillis(); }

    public int getPlayerPoints(UUID uuid)    { return competitionService == null ? 0  : competitionService.getPlayerPoints(uuid); }
    public int getPlayerPosition(UUID uuid)  { return competitionService == null ? -1 : competitionService.getPlayerPosition(uuid); }
    public int getGapTopOneToTwo()           { return competitionService == null ? 0  : competitionService.getGapTopOneToTwo(); }
    public int getGapToAbove(UUID uuid)      { return competitionService == null ? 0  : competitionService.getGapToAbove(uuid); }
    public int getGapToBelow(UUID uuid)      { return competitionService == null ? 0  : competitionService.getGapToBelow(uuid); }
    public int getWinsTotal(UUID uuid)       { return competitionService == null ? 0  : competitionService.getWinsTotal(uuid); }
    public int getWinsByChallenge(UUID uuid, ChallengeType type) {
        return competitionService == null ? 0 : competitionService.getWinsByChallenge(uuid, type);
    }

    public RankingEntry getCurrentTopAt(int rank)  { return competitionService == null ? null : competitionService.getCurrentTopAt(rank); }
    public WinsEntry getGlobalWinsTopAt(int rank)  { return competitionService == null ? null : competitionService.getGlobalWinsTopAt(rank); }
    public WinsEntry getChallengeWinsTopAt(ChallengeType type, int rank) {
        return competitionService == null ? null : competitionService.getChallengeWinsTopAt(type, rank);
    }

    // ── Special-slot proxies ──────────────────────────────────────────────────
    public boolean hasActiveSpecialChallenge()       { return competitionService != null && competitionService.hasActiveSpecialChallenge(); }
    public ChallengeType getActiveSpecialChallenge() { return competitionService == null ? null : competitionService.getActiveSpecialChallenge(); }
    public long getSpecialRemainingMillis()          { return competitionService == null ? 0L  : competitionService.getSpecialRemainingMillis(); }

    public int getSpecialPlayerPoints(UUID uuid)   { return competitionService == null ? 0  : competitionService.getPlayerPoints(CompetitionService.SLOT_SPECIAL, uuid); }
    public int getSpecialPlayerPosition(UUID uuid) { return competitionService == null ? -1 : competitionService.getPlayerPosition(CompetitionService.SLOT_SPECIAL, uuid); }
    public int getSpecialGapTopOneToTwo()          { return competitionService == null ? 0  : competitionService.getGapTopOneToTwo(CompetitionService.SLOT_SPECIAL); }
    public int getSpecialGapToAbove(UUID uuid)     { return competitionService == null ? 0  : competitionService.getGapToAbove(CompetitionService.SLOT_SPECIAL, uuid); }
    public int getSpecialGapToBelow(UUID uuid)     { return competitionService == null ? 0  : competitionService.getGapToBelow(CompetitionService.SLOT_SPECIAL, uuid); }
    public RankingEntry getSpecialTopAt(int rank)  { return competitionService == null ? null : competitionService.getCurrentTopAt(CompetitionService.SLOT_SPECIAL, rank); }

    // ── Block / entity proxies ────────────────────────────────────────────────
    public void registerPlacedBlock(BlockKey key)     { if (competitionService != null) competitionService.registerPlacedBlock(key); }
    public boolean consumeIfPlacedByPlayer(BlockKey key) { return competitionService != null && competitionService.consumeIfPlacedByPlayer(key); }
    public void markNaturalEntity(Entity entity)      { if (competitionService != null) competitionService.markNaturalEntity(entity); }
    public void unmarkNaturalEntity(Entity entity)    { if (competitionService != null) competitionService.unmarkNaturalEntity(entity); }
    public boolean isNaturalEntity(Entity entity)     { return competitionService != null && competitionService.isNaturalEntity(entity); }
    public boolean isWorldExcluded(String worldName)  { return competitionService != null && competitionService.isWorldExcluded(worldName); }

    /** True if any active slot needs block-break events. */
    public boolean hasAnyBlockChallenge() { return competitionService != null && competitionService.hasAnyBlockChallenge(); }
    /** True if any active slot matches the given type (used by listener guards). */
    public boolean hasAnyChallengeOfType(ChallengeType type) { return competitionService != null && competitionService.hasAnyChallengeOfType(type); }

    // ── Point addition (delegated to service, which fans out to all matching slots) ─
    public void handleBlockBreak(Player player, Material material) {
        if (competitionService != null) competitionService.addBlockBreakPoints(player, material);
    }
    public void handleFishCatch(Player player, Material material, int amount) {
        if (competitionService != null) competitionService.addFishPoints(player, material, amount);
    }
    public void handleEntityKill(Player player, EntityType entityType) {
        if (competitionService != null) competitionService.addKillPoints(player, entityType);
    }

    // ── Admin operations (daily) ──────────────────────────────────────────────
    public void startAdminChallenge(ChallengeType type) {
        if (competitionService != null) competitionService.startAdminChallenge(type);
    }
    public void startAdminChallengeUntilScheduleEnd(ChallengeType type) {
        if (competitionService != null) {
            long slotEnd = dailyScheduleService != null
                    ? dailyScheduleService.getCurrentOrNextSlotEndMillis()
                    : System.currentTimeMillis() + getConfig().getLong("challenge.duration-minutes", 30L) * 60_000L;
            competitionService.startAdminChallengeUntilScheduleEnd(type, slotEnd);
        }
    }
    public void stopAdminChallenge() {
        if (competitionService != null) competitionService.stopAdminChallenge();
    }
    public void stopAdminChallengeNoRewards() {
        if (competitionService != null) competitionService.stopAdminChallengeNoRewards();
        if (dailyScheduleService != null) dailyScheduleService.suppressAutoStartUntilWindowExit();
    }

    // ── Admin operations (special) ────────────────────────────────────────────
    public void startAdminSpecialChallenge(ChallengeType type) {
        if (competitionService != null) competitionService.startAdminSpecialChallenge(type);
    }
    public void startAdminSpecialChallengeUntilScheduleEnd(ChallengeType type) {
        if (competitionService != null) {
            long slotEnd = specialEventScheduleService != null
                    ? specialEventScheduleService.getCurrentOrNextSlotEndMillis()
                    : System.currentTimeMillis() + getConfig().getLong("special-event.duration-minutes",
                            getConfig().getLong("challenge.duration-minutes", 30L)) * 60_000L;
            competitionService.startAdminSpecialChallengeUntilScheduleEnd(type, slotEnd);
        }
    }
    public void stopAdminSpecialChallenge() {
        if (competitionService != null) competitionService.stopAdminSpecialChallenge();
    }
    public void stopAdminSpecialChallengeNoRewards() {
        if (competitionService != null) competitionService.stopAdminSpecialChallengeNoRewards();
        if (specialEventScheduleService != null) specialEventScheduleService.suppressAutoStartUntilWindowExit();
    }

    // ── Schedule-managed operations (used by DailyScheduleService) ───────────
    public void startScheduledChallenge(String slotId, ChallengeType type, long slotEndMillis) {
        if (competitionService != null) competitionService.startScheduledChallenge(slotId, type, slotEndMillis);
    }
    public void stopScheduledChallenge(String slotId) {
        if (competitionService != null) competitionService.stopScheduledChallenge(slotId);
    }

    // ── Display ───────────────────────────────────────────────────────────────
    public Component statusLine() {
        return competitionService == null ? renderPrefixed("&cNo hay reto activo.") : competitionService.statusLine();
    }
    public void sendUnifiedTop(CommandSender sender) {
        if (competitionService != null) competitionService.sendUnifiedTop(sender);
    }
    public void sendDebug(CommandSender sender) {
        if (competitionService != null) competitionService.sendDebug(sender, placeholderExpansion != null);
    }

    // ── Misc admin ────────────────────────────────────────────────────────────
    public void updateDurationMinutes(long minutes) {
        if (competitionService != null) competitionService.updateDurationMinutes(minutes);
    }
    public void resetPlacedCache() {
        if (competitionService != null) competitionService.resetPlacedCache();
    }
    public boolean forceNpcSkinRefresh() {
        return fancyNpcSkinRefreshService != null && fancyNpcSkinRefreshService.forceRefreshNow();
    }
    public boolean handlePointEdit(CommandSender sender, String[] args, String label, PointOperation operation) {
        return competitionService != null && competitionService.handlePointEdit(sender, args, label, operation);
    }
    public boolean handlePointEditSpecial(CommandSender sender, String[] args, String label, PointOperation operation) {
        return competitionService != null
                && competitionService.handlePointEdit(sender, args, label, operation, CompetitionService.SLOT_SPECIAL);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────
    private void saveResourceIfMissing(String name) {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        java.io.File file = new java.io.File(getDataFolder(), name);
        if (!file.exists()) saveResource(name, false);
    }

    private void registerPlaceholders() {
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) return;
        try {
            placeholderExpansion = new VCompetitionPlaceholderExpansion(this);
            placeholderExpansion.register();
        } catch (NoClassDefFoundError error) {
            getLogger().warning("PlaceholderAPI detectado pero no se pudo registrar expansión: " + error.getMessage());
        }
    }

    public Component renderPrefixed(String raw) {
        if (messageService == null) return Component.text(raw == null ? "" : raw);
        return messageService.renderPrefixed(raw);
    }

    // ── Inner types ───────────────────────────────────────────────────────────
    public enum PointOperation {
        SET("edit"),
        ADD("addpoints"),
        REMOVE("removepoints");

        private final String commandName;
        PointOperation(String commandName) { this.commandName = commandName; }
        public String commandName() { return commandName; }
    }

    public record RankingEntry(UUID uuid, String name, int points) {}
    public record WinsEntry(UUID uuid, String name, int wins) {}
}
