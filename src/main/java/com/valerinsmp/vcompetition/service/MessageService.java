package com.valerinsmp.vcompetition.service;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MessageService {
    private final VCompetitionPlugin plugin;
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacySerializer;

    private File messagesFile;
    private FileConfiguration messagesConfig;

    public MessageService(VCompetitionPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.legacySerializer = LegacyComponentSerializer.legacyAmpersand();
        reload();
    }

    public void reload() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public Component renderPrefixed(String raw) {
        String prefix = getString("messages.prefix", "&8[<gradient:#FFD166:#FF9F1C>ᴛᴏʀɴᴇᴏꜱ</gradient>&8] <reset>");
        Component prefixComponent = renderSegment(prefix);
        Component bodyComponent = renderSegment(raw);
        return prefixComponent.append(bodyComponent);
    }

    public Component renderRaw(String raw) {
        return renderSegment(raw);
    }

    private Component renderSegment(String raw) {
        String safe = raw == null ? "" : raw;
        if (safe.contains("&")) {
            return legacySerializer.deserialize(safe);
        }
        if (containsMiniMessageTag(safe)) {
            return miniMessage.deserialize(safe);
        }
        return Component.text(safe);
    }

    private boolean containsMiniMessageTag(String raw) {
        return raw.contains("<red>")
                || raw.contains("<green>")
                || raw.contains("<yellow>")
                || raw.contains("<gold>")
                || raw.contains("<aqua>")
                || raw.contains("<gray>")
                || raw.contains("<dark_gray>")
                || raw.contains("<white>")
                || raw.contains("<reset>")
                || raw.contains("<gradient:")
                || raw.contains("</gradient>");
    }

    public String applyPlaceholders(String raw, String challenge, String player, String victim, int points, int rank, int wins) {
        String safe = raw == null ? "" : raw;
        return safe
                .replace("%challenge%", challenge == null ? "none" : challenge)
                .replace("%player%", player == null ? "" : player)
                .replace("%victim%", victim == null ? "" : victim)
                .replace("%points%", String.valueOf(points))
                .replace("%rank%", String.valueOf(rank))
                .replace("%wins%", String.valueOf(wins));
    }

    public String applyPlaceholders(String raw, Map<String, String> placeholders) {
        String out = raw == null ? "" : raw;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }

    public String getString(String path, String fallback) {
        return messagesConfig == null ? fallback : messagesConfig.getString(path, fallback);
    }

    public List<String> getLines(String path, List<String> fallback) {
        if (messagesConfig == null || !messagesConfig.contains(path)) {
            return fallback;
        }
        Object raw = messagesConfig.get(path);
        if (raw instanceof List<?> list) {
            List<String> lines = new ArrayList<>();
            for (Object item : list) {
                lines.add(String.valueOf(item));
            }
            return lines;
        }
        return List.of(String.valueOf(raw));
    }

    public void sendPath(CommandSender sender, String path, List<String> fallback, Map<String, String> placeholders) {
        for (String line : getLines(path, fallback)) {
            sender.sendMessage(renderConfiguredLine(line, placeholders));
        }
    }

    public void sendPath(Player player, String path, List<String> fallback, Map<String, String> placeholders) {
        for (String line : getLines(path, fallback)) {
            player.sendMessage(renderConfiguredLine(line, placeholders));
        }
    }

    public void broadcastPath(String path, List<String> fallback, Map<String, String> placeholders) {
        for (String line : getLines(path, fallback)) {
            broadcast(renderConfiguredLine(line, placeholders));
        }
    }

    public Map<String, String> placeholders(String... keyValues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put(keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    private Component renderConfiguredLine(String rawLine, Map<String, String> placeholders) {
        String line = applyPlaceholders(rawLine, placeholders);
        String prefix = getString("messages.prefix", "&8[<gradient:#FFD166:#FF9F1C>ᴛᴏʀɴᴇᴏꜱ</gradient>&8] <reset>");

        if (line.contains("%prefix%")) {
            return renderRaw(line.replace("%prefix%", prefix));
        }
        return renderRaw(line);
    }

    public void broadcast(Component message) {
        Bukkit.getServer().broadcast(message);
    }
}
