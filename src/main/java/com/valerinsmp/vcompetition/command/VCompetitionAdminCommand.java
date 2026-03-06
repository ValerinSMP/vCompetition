package com.valerinsmp.vcompetition.command;

import com.valerinsmp.vcompetition.model.ChallengeType;
import com.valerinsmp.vcompetition.VCompetitionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class VCompetitionAdminCommand implements TabExecutor {
    private final VCompetitionPlugin plugin;

    public VCompetitionAdminCommand(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            plugin.getMessageService().sendPath(sender, "messages.help.general", List.of(
                            "&7Comandos disponibles:",
                            "&f/%label% status",
                            "&f/%label% top",
                            "&f/%label% admin"
                    ),
                    plugin.getMessageService().placeholders("%label%", label));
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(plugin.statusLine());
            return true;
        }

        if (args[0].equalsIgnoreCase("top")) {
            plugin.sendTop(sender);
            return true;
        }

        if (!args[0].equalsIgnoreCase("admin")) {
            plugin.getMessageService().sendPath(sender, "messages.help.general", List.of(
                            "&7Comandos disponibles:",
                            "&f/%label% status",
                            "&f/%label% top",
                            "&f/%label% admin"
                    ),
                    plugin.getMessageService().placeholders("%label%", label));
            return true;
        }

        if (args.length == 1) {
            plugin.getMessageService().sendPath(sender, "messages.help.admin", List.of(
                            "&7Comandos admin:",
                            "&f/%label% admin start <MINING|WOODCUTTING|FISHING|SLAYER|PLAYTIME>",
                            "&f/%label% admin startuntilsunday <MINING|WOODCUTTING|FISHING|SLAYER|PLAYTIME>",
                            "&f/%label% admin stop",
                            "&f/%label% admin stopnorewards",
                            "&f/%label% admin status",
                            "&f/%label% admin top",
                            "&f/%label% admin edit <jugador> <puntos>",
                            "&f/%label% admin addpoints <jugador> <puntos>",
                            "&f/%label% admin removepoints <jugador> <puntos>",
                            "&f/%label% admin setduration <dias>",
                            "&f/%label% admin resetplaced",
                                "&f/%label% admin refreshskins",
                            "&f/%label% admin reload",
                            "&f/%label% admin debug"
                    ),
                    plugin.getMessageService().placeholders("%label%", label));
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        if (!hasSubPermission(sender, sub)) {
            plugin.getMessageService().sendPath(sender, "messages.admin.no-permission", List.of("&cNo tienes permisos para este subcomando."), Collections.emptyMap());
            return true;
        }

        switch (sub) {
            case "start" -> {
                if (args.length < 3) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.usage-start", List.of("&cUso: /%label% admin start <reto>"),
                            plugin.getMessageService().placeholders("%label%", label));
                    return true;
                }
                try {
                    ChallengeType type = ChallengeType.fromInput(args[2]);
                    plugin.startAdminChallenge(type);
                    plugin.getMessageService().sendPath(sender, "messages.admin.started", List.of("&aTorneo iniciado: &e%challenge%"),
                            plugin.getMessageService().placeholders("%challenge%", type.displayName()));
                } catch (IllegalArgumentException exception) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.invalid-challenge", List.of("&cReto inválido. Usa: MINING, WOODCUTTING, FISHING, SLAYER, PLAYTIME"), Collections.emptyMap());
                }
                return true;
            }
            case "startuntilsunday" -> {
                if (args.length < 3) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.usage-start-until-sunday", List.of("&cUso: /%label% admin startuntilsunday <reto>"),
                            plugin.getMessageService().placeholders("%label%", label));
                    return true;
                }
                try {
                    ChallengeType type = ChallengeType.fromInput(args[2]);
                    plugin.startAdminChallengeUntilScheduleEnd(type);
                    plugin.getMessageService().sendPath(sender, "messages.admin.started-until-sunday", List.of("&aTorneo iniciado hasta el cierre semanal: &e%challenge%"),
                            plugin.getMessageService().placeholders("%challenge%", type.displayName()));
                } catch (IllegalArgumentException exception) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.invalid-challenge", List.of("&cReto inválido. Usa: MINING, WOODCUTTING, FISHING, SLAYER, PLAYTIME"), Collections.emptyMap());
                }
                return true;
            }
            case "stop" -> {
                if (!plugin.hasActiveChallenge()) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.no-active", List.of("&cNo hay torneo activo."), Collections.emptyMap());
                    return true;
                }
                plugin.stopAdminChallenge();
                plugin.getMessageService().sendPath(sender, "messages.admin.stopped", List.of("&aTorneo finalizado manualmente."), Collections.emptyMap());
                return true;
            }
            case "stopnorewards" -> {
                if (!plugin.hasActiveChallenge()) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.no-active", List.of("&cNo hay torneo activo."), Collections.emptyMap());
                    return true;
                }
                plugin.stopAdminChallengeNoRewards();
                plugin.getMessageService().sendPath(sender, "messages.admin.stopped-no-rewards", List.of("&eTorneo detenido sin recompensas."), Collections.emptyMap());
                return true;
            }
            case "edit" -> {
                return plugin.handlePointEdit(sender, args, label, VCompetitionPlugin.PointOperation.SET);
            }
            case "addpoints" -> {
                return plugin.handlePointEdit(sender, args, label, VCompetitionPlugin.PointOperation.ADD);
            }
            case "removepoints" -> {
                return plugin.handlePointEdit(sender, args, label, VCompetitionPlugin.PointOperation.REMOVE);
            }
            case "reload" -> {
                plugin.reloadPluginRuntime();
                plugin.getMessageService().sendPath(sender, "messages.admin.reload-success", List.of("&aConfiguración recargada."), Collections.emptyMap());
                return true;
            }
            case "status" -> {
                sender.sendMessage(plugin.statusLine());
                return true;
            }
            case "top" -> {
                plugin.sendTop(sender);
                return true;
            }
            case "setduration" -> {
                if (args.length < 3) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.usage-setduration", List.of("&cUso: /%label% admin setduration <dias>"),
                            plugin.getMessageService().placeholders("%label%", label));
                    return true;
                }
                long days;
                try {
                    days = Long.parseLong(args[2]);
                } catch (NumberFormatException exception) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.invalid-number", List.of("&cDebes ingresar un número válido."), Collections.emptyMap());
                    return true;
                }
                if (days < 1) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.min-duration", List.of("&cLa duración mínima es 1 día."), Collections.emptyMap());
                    return true;
                }
                plugin.updateDurationDays(days);
                plugin.getMessageService().sendPath(sender, "messages.admin.duration-updated", List.of("&aDuración actualizada a &e%days% &adías."),
                        plugin.getMessageService().placeholders("%days%", String.valueOf(days)));
                return true;
            }
            case "resetplaced" -> {
                plugin.resetPlacedCache();
                plugin.getMessageService().sendPath(sender, "messages.admin.reset-placed", List.of("&aRegistro de bloques colocados reiniciado."), Collections.emptyMap());
                return true;
            }
            case "refreshskins" -> {
                boolean refreshed = plugin.forceNpcSkinRefresh();
                if (refreshed) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.refreshskins-success", List.of("&aActualización forzada de skins ejecutada."), Collections.emptyMap());
                } else {
                    plugin.getMessageService().sendPath(sender, "messages.admin.refreshskins-failed", List.of("&cNo se pudo actualizar skins. Revisa si FancyNpcs y skin-refresh están habilitados."), Collections.emptyMap());
                }
                return true;
            }
            case "debug" -> {
                plugin.sendDebug(sender);
                return true;
            }
            default -> {
                plugin.getMessageService().sendPath(sender, "messages.admin.invalid-subcommand", List.of("&cSubcomando inválido."), Collections.emptyMap());
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterPrefix(List.of("admin", "status", "top", "help"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            List<String> subcommands = List.of("start", "startuntilsunday", "stop", "stopnorewards", "edit", "debug", "reload", "status", "top", "addpoints", "removepoints", "setduration", "resetplaced", "refreshskins");
            List<String> allowed = new ArrayList<>();
            for (String sub : subcommands) {
                if (hasSubPermission(sender, sub)) {
                    allowed.add(sub);
                }
            }
            return filterPrefix(allowed, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("start") || args[1].equalsIgnoreCase("startuntilsunday"))) {
            return filterPrefix(List.of("MINING", "WOODCUTTING", "FISHING", "SLAYER", "PLAYTIME"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && List.of("edit", "addpoints", "removepoints").contains(args[1].toLowerCase(Locale.ROOT))) {
            List<String> online = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                online.add(player.getName());
            }
            return filterPrefix(online, args[2]);
        }
        return Collections.emptyList();
    }

    private boolean hasSubPermission(CommandSender sender, String sub) {
        return sender.hasPermission("vcompetition.admin")
                || sender.hasPermission("vcompetition.admin.*")
                || sender.hasPermission("vcompetition.admin." + sub.toLowerCase(Locale.ROOT));
    }

    private List<String> filterPrefix(Collection<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
