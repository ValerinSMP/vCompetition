package com.valerinsmp.vcompetition.command;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.ChallengeType;
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

    private static final List<String> ALL_TYPES =
            List.of("MINING", "WOODCUTTING", "FISHING", "SLAYER", "FARMING", "OTONO");
    private static final List<String> DAILY_TYPES =
            List.of("MINING", "WOODCUTTING", "FISHING", "SLAYER", "FARMING");

    private final VCompetitionPlugin plugin;

    public VCompetitionAdminCommand(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            plugin.getMessageService().sendPath(sender, "messages.help.admin",
                    List.of("&7/%label% <subcomando>"),
                    plugin.getMessageService().placeholders("%label%", label));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!hasSubPermission(sender, sub)) {
            plugin.getMessageService().sendPath(sender, "messages.admin.no-permission",
                    List.of("&cNo tienes permisos para este subcomando."), Collections.emptyMap());
            return true;
        }

        switch (sub) {
            // ── Daily challenge ───────────────────────────────────────────────
            case "start" -> {
                if (args.length < 2) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.usage-start",
                            List.of("&cUso: /%label% start <tipo>"),
                            plugin.getMessageService().placeholders("%label%", label));
                    return true;
                }
                try {
                    ChallengeType type = ChallengeType.fromInput(args[1]);
                    plugin.startAdminChallenge(type);
                    plugin.getMessageService().sendPath(sender, "messages.admin.started",
                            List.of("&aTorneo iniciado: &e%challenge%"),
                            plugin.getMessageService().placeholders("%challenge%", plugin.getMessageService().challengeDisplayName(type)));
                } catch (IllegalArgumentException e) {
                    sendInvalidChallenge(sender, false);
                }
                return true;
            }
            case "startuntilsunday" -> {
                if (args.length < 2) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.usage-start-until-sunday",
                            List.of("&cUso: /%label% startuntilsunday <tipo>"),
                            plugin.getMessageService().placeholders("%label%", label));
                    return true;
                }
                try {
                    ChallengeType type = ChallengeType.fromInput(args[1]);
                    plugin.startAdminChallengeUntilScheduleEnd(type);
                    plugin.getMessageService().sendPath(sender, "messages.admin.started-until-sunday",
                            List.of("&aTorneo hasta cierre del slot: &e%challenge%"),
                            plugin.getMessageService().placeholders("%challenge%", plugin.getMessageService().challengeDisplayName(type)));
                } catch (IllegalArgumentException e) {
                    sendInvalidChallenge(sender, false);
                }
                return true;
            }
            case "stop" -> {
                if (!plugin.hasActiveChallenge()) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.no-active",
                            List.of("&cNo hay torneo diario activo."), Collections.emptyMap());
                    return true;
                }
                plugin.stopAdminChallenge();
                plugin.getMessageService().sendPath(sender, "messages.admin.stopped",
                        List.of("&aTorneo finalizado."), Collections.emptyMap());
                return true;
            }
            case "stopnorewards" -> {
                if (!plugin.hasActiveChallenge()) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.no-active",
                            List.of("&cNo hay torneo diario activo."), Collections.emptyMap());
                    return true;
                }
                plugin.stopAdminChallengeNoRewards();
                plugin.getMessageService().sendPath(sender, "messages.admin.stopped-no-rewards",
                        List.of("&eTorneo detenido sin recompensas."), Collections.emptyMap());
                return true;
            }

            // ── Special event ─────────────────────────────────────────────────
            case "startspecial" -> {
                if (args.length < 2) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.usage-startspecial",
                            List.of("&cUso: /%label% startspecial <tipo>"),
                            plugin.getMessageService().placeholders("%label%", label));
                    return true;
                }
                try {
                    ChallengeType type = ChallengeType.fromInput(args[1]);
                    plugin.startAdminSpecialChallenge(type);
                    plugin.getMessageService().sendPath(sender, "messages.admin.started-special",
                            List.of("&aEvento especial iniciado: &e%challenge%"),
                            plugin.getMessageService().placeholders("%challenge%", plugin.getMessageService().challengeDisplayName(type)));
                } catch (IllegalArgumentException e) {
                    sendInvalidChallenge(sender, true);
                }
                return true;
            }
            case "stopspecial" -> {
                if (!plugin.hasActiveSpecialChallenge()) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.no-active-special",
                            List.of("&cNo hay evento especial activo."), Collections.emptyMap());
                    return true;
                }
                plugin.stopAdminSpecialChallenge();
                plugin.getMessageService().sendPath(sender, "messages.admin.stopped-special",
                        List.of("&aEvento especial finalizado."), Collections.emptyMap());
                return true;
            }
            case "stopspecialnorewards" -> {
                if (!plugin.hasActiveSpecialChallenge()) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.no-active-special",
                            List.of("&cNo hay evento especial activo."), Collections.emptyMap());
                    return true;
                }
                plugin.stopAdminSpecialChallengeNoRewards();
                plugin.getMessageService().sendPath(sender, "messages.admin.stopped-special-no-rewards",
                        List.of("&eEvento especial detenido sin recompensas."), Collections.emptyMap());
                return true;
            }

            // ── Point editing (daily) ─────────────────────────────────────────
            case "edit"         -> { return plugin.handlePointEdit(sender, args, label, VCompetitionPlugin.PointOperation.SET); }
            case "addpoints"    -> { return plugin.handlePointEdit(sender, args, label, VCompetitionPlugin.PointOperation.ADD); }
            case "removepoints" -> { return plugin.handlePointEdit(sender, args, label, VCompetitionPlugin.PointOperation.REMOVE); }

            // ── Point editing (special) ───────────────────────────────────────
            case "editspecial"         -> { return plugin.handlePointEditSpecial(sender, args, label, VCompetitionPlugin.PointOperation.SET); }
            case "addpointsspecial"    -> { return plugin.handlePointEditSpecial(sender, args, label, VCompetitionPlugin.PointOperation.ADD); }
            case "removepointsspecial" -> { return plugin.handlePointEditSpecial(sender, args, label, VCompetitionPlugin.PointOperation.REMOVE); }

            // ── Misc ──────────────────────────────────────────────────────────
            case "reload" -> {
                plugin.reloadPluginRuntime();
                plugin.getMessageService().sendPath(sender, "messages.admin.reload-success",
                        List.of("&aConfiguración recargada."), Collections.emptyMap());
                return true;
            }
            case "status" -> {
                sender.sendMessage(plugin.statusLine());
                return true;
            }
            case "top" -> {
                plugin.sendUnifiedTop(sender);
                return true;
            }
            case "setduration" -> {
                if (args.length < 2) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.usage-setduration",
                            List.of("&cUso: /%label% setduration <minutos>"),
                            plugin.getMessageService().placeholders("%label%", label));
                    return true;
                }
                try {
                    long minutes = Long.parseLong(args[1]);
                    if (minutes < 1) {
                        plugin.getMessageService().sendPath(sender, "messages.admin.min-duration",
                                List.of("&cLa duración mínima es 1 minuto."), Collections.emptyMap());
                        return true;
                    }
                    plugin.updateDurationMinutes(minutes);
                    plugin.getMessageService().sendPath(sender, "messages.admin.duration-updated",
                            List.of("&aDuración actualizada a &e%minutes% &aminutos."),
                            plugin.getMessageService().placeholders("%minutes%", String.valueOf(minutes)));
                } catch (NumberFormatException e) {
                    plugin.getMessageService().sendPath(sender, "messages.admin.invalid-number",
                            List.of("&cDebes ingresar un número válido."), Collections.emptyMap());
                }
                return true;
            }
            case "resetplaced" -> {
                plugin.resetPlacedCache();
                plugin.getMessageService().sendPath(sender, "messages.admin.reset-placed",
                        List.of("&aRegistro de bloques colocados reiniciado."), Collections.emptyMap());
                return true;
            }
            case "refreshskins" -> {
                boolean ok = plugin.forceNpcSkinRefresh();
                plugin.getMessageService().sendPath(sender,
                        ok ? "messages.admin.refreshskins-success" : "messages.admin.refreshskins-failed",
                        List.of(ok ? "&aActualización forzada de skins ejecutada."
                                   : "&cNo se pudo actualizar skins. Revisa si FancyNpcs y skin-refresh están habilitados."),
                        Collections.emptyMap());
                return true;
            }
            case "debug" -> {
                plugin.sendDebug(sender);
                return true;
            }
            default -> {
                plugin.getMessageService().sendPath(sender, "messages.admin.invalid-subcommand",
                        List.of("&cSubcomando inválido."), Collections.emptyMap());
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = List.of(
                    "start", "startuntilsunday", "stop", "stopnorewards",
                    "startspecial", "stopspecial", "stopspecialnorewards",
                    "edit", "addpoints", "removepoints",
                    "editspecial", "addpointsspecial", "removepointsspecial",
                    "top", "status",
                    "setduration", "resetplaced", "refreshskins", "reload", "debug", "help");
            List<String> allowed = new ArrayList<>();
            for (String s : subs) {
                if (hasSubPermission(sender, s)) allowed.add(s);
            }
            return filterPrefix(allowed, args[0]);
        }
        if (args.length == 2) {
            String s = args[0].toLowerCase(Locale.ROOT);
            if (s.equals("start") || s.equals("startuntilsunday")) {
                return filterPrefix(DAILY_TYPES, args[1]);
            }
            if (s.equals("startspecial")) {
                return filterPrefix(ALL_TYPES, args[1]);
            }
            if (List.of("edit", "addpoints", "removepoints",
                        "editspecial", "addpointsspecial", "removepointsspecial").contains(s)) {
                List<String> online = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getName());
                return filterPrefix(online, args[1]);
            }
        }
        return Collections.emptyList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendInvalidChallenge(CommandSender sender, boolean includeOtono) {
        String types = includeOtono
                ? "MINING, WOODCUTTING, FISHING, SLAYER, FARMING, OTONO"
                : "MINING, WOODCUTTING, FISHING, SLAYER, FARMING";
        plugin.getMessageService().sendPath(sender, "messages.admin.invalid-challenge",
                List.of("%prefix%<red>Tipo inválido. Opciones: <yellow>%types%"),
                plugin.getMessageService().placeholders("%types%", types));
    }

    private boolean hasSubPermission(CommandSender sender, String sub) {
        return sender.hasPermission("vcompetition.admin")
                || sender.hasPermission("vcompetition.admin.*")
                || sender.hasPermission("vcompetition.admin." + sub.toLowerCase(Locale.ROOT));
    }

    private List<String> filterPrefix(Collection<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(o);
        }
        return out;
    }
}
