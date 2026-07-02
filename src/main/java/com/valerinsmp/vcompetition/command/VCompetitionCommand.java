package com.valerinsmp.vcompetition.command;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.ChallengeType;
import com.valerinsmp.vcompetition.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class VCompetitionCommand implements TabExecutor {

    private static final List<String> ALL_TYPES =
            List.of("MINING", "WOODCUTTING", "FISHING", "SLAYER", "FARMING", "OTONO");

    private final VCompetitionPlugin plugin;

    public VCompetitionCommand(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            plugin.getMessageService().sendPath(sender, "messages.help.general",
                    List.of("&7/%label% | status | top | info | help"),
                    plugin.getMessageService().placeholders("%label%", label));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                sender.sendMessage(plugin.statusLine());
                return true;
            }
            case "top" -> {
                plugin.sendUnifiedTop(sender);
                return true;
            }
            case "info" -> {
                if (args.length >= 2) {
                    sendInfoDetail(sender, args[1]);
                } else {
                    sendInfoOverview(sender);
                }
                return true;
            }
            default -> {
                plugin.getMessageService().sendPath(sender, "messages.commands.invalid-subcommand",
                        List.of("%prefix%<red>Subcomando inválido. Usa <yellow>/%label% help</yellow>."),
                        plugin.getMessageService().placeholders("%label%", label));
                return true;
            }
        }
    }

    /** `/torneos info` — one short line per challenge type, click to suggest the detail command. */
    private void sendInfoOverview(CommandSender sender) {
        MessageService messages = plugin.getMessageService();
        for (String line : messages.getLines("messages.info.header", List.of(" "))) {
            sender.sendMessage(messages.renderRaw(line));
        }
        String template = messages.getString("messages.info.line", " %challenge% - %description%");
        for (ChallengeType type : ChallengeType.values()) {
            String description = messages.getString("messages.info.descriptions." + type.name(), "");
            String rendered = messages.applyPlaceholders(template, messages.placeholders(
                    "%challenge%", messages.challengeDisplayName(type),
                    "%description%", description,
                    "%type_raw%", type.name().toLowerCase(Locale.ROOT)));
            sender.sendMessage(messages.renderRaw(rendered));
        }
    }

    /** `/torneos info <tipo>` — full comma-separated breakdown, translated to the client's own language. */
    private void sendInfoDetail(CommandSender sender, String rawType) {
        MessageService messages = plugin.getMessageService();
        ChallengeType type;
        try {
            type = ChallengeType.fromInput(rawType);
        } catch (IllegalArgumentException exception) {
            messages.sendPath(sender, "messages.info.invalid-type",
                    List.of("%prefix%<red>Tipo inválido. Opciones: <yellow>%types%"),
                    messages.placeholders("%types%", String.join(", ", ALL_TYPES)));
            return;
        }

        String header = messages.applyPlaceholders(
                messages.getString("messages.info.detail-header", "%challenge%"),
                messages.placeholders("%challenge%", messages.challengeDisplayName(type)));
        sender.sendMessage(messages.renderRaw(header));

        List<Component> parts = new ArrayList<>();
        String typePath = "competition-types." + type.name().toLowerCase(Locale.ROOT);
        if (type == ChallengeType.SLAYER) {
            for (String raw : plugin.getConfig().getStringList(typePath + ".mobs")) {
                try {
                    parts.add(Component.translatable(EntityType.valueOf(raw.toUpperCase(Locale.ROOT)))
                            .color(NamedTextColor.YELLOW));
                } catch (IllegalArgumentException ignored) {
                    // invalid entries are already logged by CompetitionService on config reload
                }
            }
        } else {
            for (String raw : plugin.getConfig().getStringList(typePath + ".materials")) {
                Material material = Material.matchMaterial(raw);
                if (material != null) {
                    parts.add(Component.translatable(material).color(NamedTextColor.YELLOW));
                }
            }
        }

        if (parts.isEmpty()) {
            sender.sendMessage(messages.renderRaw(
                    messages.getString("messages.info.detail-empty", " Sin datos.")));
            return;
        }

        Component separator = Component.text(", ", NamedTextColor.DARK_GRAY);
        sender.sendMessage(Component.join(JoinConfiguration.separator(separator), parts));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterPrefix(List.of("status", "top", "info", "help"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            return filterPrefix(ALL_TYPES, args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterPrefix(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(o);
        }
        return out;
    }
}
