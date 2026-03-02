package com.valerinsmp.vcompetition;

import com.valerinsmp.vcompetition.command.VCompetitionAdminCommand;
import com.valerinsmp.vcompetition.listener.CompetitionListener;
import com.valerinsmp.vcompetition.model.BlockKey;
import com.valerinsmp.vcompetition.model.ChallengeType;
import com.valerinsmp.vcompetition.placeholder.VCompetitionPlaceholderExpansion;
import com.valerinsmp.vcompetition.service.CompetitionService;
import com.valerinsmp.vcompetition.service.FancyNpcSkinRefreshService;
import com.valerinsmp.vcompetition.service.MessageService;
import com.valerinsmp.vcompetition.service.SoundService;
import com.valerinsmp.vcompetition.service.WeeklyScheduleService;
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
    private WeeklyScheduleService weeklyScheduleService;
    private FancyNpcSkinRefreshService fancyNpcSkinRefreshService;
    private VCompetitionPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("sounds.yml");

        sqliteManager = new SQLiteManager(this);
        messageService = new MessageService(this);
        soundService = new SoundService(this);
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
        VCompetitionAdminCommand adminCommand = new VCompetitionAdminCommand(this);
        if (getCommand("vcompetition") != null) {
            getCommand("vcompetition").setExecutor(adminCommand);
            getCommand("vcompetition").setTabCompleter(adminCommand);
        }

        weeklyScheduleService = new WeeklyScheduleService(this);
        weeklyScheduleService.start();
        fancyNpcSkinRefreshService = new FancyNpcSkinRefreshService(this);
        fancyNpcSkinRefreshService.start();
        registerPlaceholders();
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }

        if (weeklyScheduleService != null) {
            weeklyScheduleService.stop();
            weeklyScheduleService = null;
        }

        if (fancyNpcSkinRefreshService != null) {
            fancyNpcSkinRefreshService.stop();
            fancyNpcSkinRefreshService = null;
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
        soundService = null;
    }

    private void saveResourceIfMissing(String name) {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        java.io.File file = new java.io.File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public SoundService getSoundService() {
        return soundService;
    }

    public boolean isRuntimeActive() {
        return competitionService != null && competitionService.isRuntimeActive();
    }

    public boolean hasActiveChallenge() {
        return competitionService != null && competitionService.hasActiveChallenge();
    }

    public boolean isScheduleManagedChallengeActive() {
        return competitionService != null && competitionService.isScheduleManagedChallengeActive();
    }

    public ChallengeType getActiveChallenge() {
        return competitionService == null ? null : competitionService.getActiveChallenge();
    }

    public long getRemainingMillis() {
        return competitionService == null ? 0L : competitionService.getRemainingMillis();
    }

    public int getPlayerPoints(UUID uuid) {
        return competitionService == null ? 0 : competitionService.getPlayerPoints(uuid);
    }

    public int getPlayerPosition(UUID uuid) {
        return competitionService == null ? -1 : competitionService.getPlayerPosition(uuid);
    }

    public int getGapTopOneToTwo() {
        return competitionService == null ? 0 : competitionService.getGapTopOneToTwo();
    }

    public int getGapToAbove(UUID uuid) {
        return competitionService == null ? 0 : competitionService.getGapToAbove(uuid);
    }

    public int getGapToBelow(UUID uuid) {
        return competitionService == null ? 0 : competitionService.getGapToBelow(uuid);
    }

    public int getWinsTotal(UUID uuid) {
        return competitionService == null ? 0 : competitionService.getWinsTotal(uuid);
    }

    public int getWinsByChallenge(UUID uuid, ChallengeType challengeType) {
        return competitionService == null ? 0 : competitionService.getWinsByChallenge(uuid, challengeType);
    }

    public RankingEntry getCurrentTopAt(int rank) {
        return competitionService == null ? null : competitionService.getCurrentTopAt(rank);
    }

    public WinsEntry getGlobalWinsTopAt(int rank) {
        return competitionService == null ? null : competitionService.getGlobalWinsTopAt(rank);
    }

    public WinsEntry getChallengeWinsTopAt(ChallengeType challengeType, int rank) {
        return competitionService == null ? null : competitionService.getChallengeWinsTopAt(challengeType, rank);
    }

    public void registerPlacedBlock(BlockKey blockKey) {
        if (competitionService != null) {
            competitionService.registerPlacedBlock(blockKey);
        }
    }

    public boolean consumeIfPlacedByPlayer(BlockKey blockKey) {
        return competitionService != null && competitionService.consumeIfPlacedByPlayer(blockKey);
    }

    public void markNaturalEntity(Entity entity) {
        if (competitionService != null) {
            competitionService.markNaturalEntity(entity);
        }
    }

    public boolean isNaturalEntity(Entity entity) {
        return competitionService != null && competitionService.isNaturalEntity(entity);
    }

    public boolean isMiningMaterial(Material material) {
        return competitionService != null && competitionService.isMiningMaterial(material);
    }

    public boolean isWoodMaterial(Material material) {
        return competitionService != null && competitionService.isWoodMaterial(material);
    }

    public boolean isFishingMaterial(Material material) {
        return competitionService != null && competitionService.isFishingMaterial(material);
    }

    public boolean isSlayerMob(EntityType entityType) {
        return competitionService != null && competitionService.isSlayerMob(entityType);
    }

    public boolean isWorldExcluded(String worldName) {
        return competitionService != null && competitionService.isWorldExcluded(worldName);
    }

    public void addPoints(Player player, int amount) {
        if (competitionService != null) {
            competitionService.addPoints(player, amount);
        }
    }

    public boolean handlePointEdit(CommandSender sender, String[] args, String label, PointOperation operation) {
        return competitionService != null && competitionService.handlePointEdit(sender, args, label, operation);
    }

    public void startAdminChallenge(ChallengeType type) {
        if (competitionService != null) {
            competitionService.startAdminChallenge(type);
        }
    }

    public void stopAdminChallenge() {
        if (competitionService != null) {
            competitionService.stopAdminChallenge();
        }
    }

    public void startScheduledChallenge(ChallengeType type) {
        if (competitionService != null) {
            competitionService.startScheduledChallenge(type);
        }
    }

    public void stopScheduledChallenge() {
        if (competitionService != null) {
            competitionService.stopScheduledChallenge();
        }
    }

    public void reloadPluginRuntime() {
        reloadConfig();
        if (messageService != null) {
            messageService.reload();
        }
        if (soundService != null) {
            soundService.reload();
        }
        if (competitionService != null) {
            competitionService.loadCompetitionRules();
        }
        if (weeklyScheduleService != null) {
            weeklyScheduleService.start();
        }
        if (fancyNpcSkinRefreshService != null) {
            fancyNpcSkinRefreshService.start();
        }
    }

    public Component statusLine() {
        return competitionService == null ? renderPrefixed("&cNo hay reto activo.") : competitionService.statusLine();
    }

    public void sendTop(CommandSender sender) {
        if (competitionService != null) {
            competitionService.sendTop(sender);
        }
    }

    public void updateDurationDays(long days) {
        if (competitionService != null) {
            competitionService.updateDurationDays(days);
        }
    }

    public void resetPlacedCache() {
        if (competitionService != null) {
            competitionService.resetPlacedCache();
        }
    }

    public void sendDebug(CommandSender sender) {
        if (competitionService != null) {
            competitionService.sendDebug(sender, placeholderExpansion != null);
        }
    }

    public boolean forceNpcSkinRefresh() {
        return fancyNpcSkinRefreshService != null && fancyNpcSkinRefreshService.forceRefreshNow();
    }

    private void registerPlaceholders() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            placeholderExpansion = new VCompetitionPlaceholderExpansion(this);
            placeholderExpansion.register();
        } catch (NoClassDefFoundError error) {
            getLogger().warning("PlaceholderAPI detectado pero no se pudo registrar expansión: " + error.getMessage());
        }
    }

    public Component renderPrefixed(String raw) {
        if (messageService == null) {
            return Component.text(raw == null ? "" : raw);
        }
        return messageService.renderPrefixed(raw);
    }

    public enum PointOperation {
        SET("edit"),
        ADD("addpoints"),
        REMOVE("removepoints");

        private final String commandName;

        PointOperation(String commandName) {
            this.commandName = commandName;
        }

        public String commandName() {
            return commandName;
        }
    }

    public record RankingEntry(UUID uuid, String name, int points) {
    }

    public record WinsEntry(UUID uuid, String name, int wins) {
    }
}
