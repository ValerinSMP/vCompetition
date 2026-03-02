package com.valerinsmp.vcompetition.service;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;

public final class SoundService {
    private final VCompetitionPlugin plugin;

    private FileConfiguration soundsConfig;

    public SoundService(VCompetitionPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File soundsFile = new File(plugin.getDataFolder(), "sounds.yml");
        soundsConfig = YamlConfiguration.loadConfiguration(soundsFile);
    }

    public void playTournamentStart() {
        playAll("sounds.tournament-start", "UI_TOAST_CHALLENGE_COMPLETE", 1.0f, 1.0f);
    }

    public void playTournamentEnd() {
        playAll("sounds.tournament-end", "ENTITY_ENDER_DRAGON_GROWL", 1.0f, 1.0f);
    }

    public void playOutrank(Player actor, Player victim) {
        playPlayer(actor, "sounds.outrank", "ENTITY_EXPERIENCE_ORB_PICKUP", 0.8f, 1.4f);
        playPlayer(victim, "sounds.outrank", "ENTITY_EXPERIENCE_ORB_PICKUP", 0.8f, 1.4f);
    }

    private void playAll(String path, String defaultSound, float defaultVolume, float defaultPitch) {
        if (!isEnabled(path)) {
            return;
        }
        Sound sound = parseSound(soundsConfig.getString(path + ".sound", defaultSound));
        if (sound == null) {
            return;
        }
        float volume = (float) soundsConfig.getDouble(path + ".volume", defaultVolume);
        float pitch = (float) soundsConfig.getDouble(path + ".pitch", defaultPitch);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    private void playPlayer(Player player, String path, String defaultSound, float defaultVolume, float defaultPitch) {
        if (player == null || !player.isOnline() || !isEnabled(path)) {
            return;
        }
        Sound sound = parseSound(soundsConfig.getString(path + ".sound", defaultSound));
        if (sound == null) {
            return;
        }
        float volume = (float) soundsConfig.getDouble(path + ".volume", defaultVolume);
        float pitch = (float) soundsConfig.getDouble(path + ".pitch", defaultPitch);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private boolean isEnabled(String path) {
        return soundsConfig.getBoolean(path + ".enabled", true);
    }

    private Sound parseSound(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Sound.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Sound inválido en sounds.yml: " + raw);
            return null;
        }
    }
}
