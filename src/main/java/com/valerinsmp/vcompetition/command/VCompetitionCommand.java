package com.valerinsmp.vcompetition.command;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class VCompetitionCommand implements TabExecutor {

    private final VCompetitionPlugin plugin;

    public VCompetitionCommand(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            plugin.getMessageService().sendPath(sender, "messages.help.general",
                    List.of("&7/%label% | status | top | help"),
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
            default -> {
                plugin.getMessageService().sendPath(sender, "messages.commands.invalid-subcommand",
                        List.of("%prefix%<red>Subcomando inválido. Usa <yellow>/%label% help</yellow>."),
                        plugin.getMessageService().placeholders("%label%", label));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterPrefix(List.of("status", "top", "help"), args[0]);
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
