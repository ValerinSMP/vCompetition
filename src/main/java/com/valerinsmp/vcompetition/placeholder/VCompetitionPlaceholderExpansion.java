package com.valerinsmp.vcompetition.placeholder;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.ChallengeType;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class VCompetitionPlaceholderExpansion extends PlaceholderExpansion {
    private final VCompetitionPlugin plugin;

    public VCompetitionPlaceholderExpansion(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "vcompetition";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(",", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);

        if (key.equals("challenge")) {
            ChallengeType type = plugin.getActiveChallenge();
            return type == null ? "ɴɪɴɢᴜɴᴏ" : type.displayName();
        }

        if (key.equals("challenge_raw")) {
            ChallengeType type = plugin.getActiveChallenge();
            return type == null ? "none" : type.name();
        }

        if (key.equals("challenge_pretty")) {
            ChallengeType type = plugin.getActiveChallenge();
            return formatChallengePretty(type);
        }

        if (key.equals("time_left")) {
            return formatDuration(plugin.getRemainingMillis());
        }

        if (key.equals("gap_top12")) {
            return String.valueOf(plugin.getGapTopOneToTwo());
        }

        if (key.startsWith("top_") && key.endsWith("_name")) {
            int rank = parseRank(key, "top_", "_name");
            if (rank < 1) {
                return "";
            }
            VCompetitionPlugin.RankingEntry entry = plugin.getCurrentTopAt(rank);
            return entry == null ? "none" : entry.name();
        }

        if (key.startsWith("top_") && key.endsWith("_points")) {
            int rank = parseRank(key, "top_", "_points");
            if (rank < 1) {
                return "0";
            }
            VCompetitionPlugin.RankingEntry entry = plugin.getCurrentTopAt(rank);
            return entry == null ? "0" : String.valueOf(entry.points());
        }

        if (key.startsWith("wins_global_top_") && key.endsWith("_name")) {
            int rank = parseRank(key, "wins_global_top_", "_name");
            if (rank < 1) {
                return "";
            }
            VCompetitionPlugin.WinsEntry entry = plugin.getGlobalWinsTopAt(rank);
            return entry == null ? "none" : entry.name();
        }

        if (key.startsWith("wins_global_top_") && key.endsWith("_wins")) {
            int rank = parseRank(key, "wins_global_top_", "_wins");
            if (rank < 1) {
                return "0";
            }
            VCompetitionPlugin.WinsEntry entry = plugin.getGlobalWinsTopAt(rank);
            return entry == null ? "0" : String.valueOf(entry.wins());
        }

        if (key.startsWith("wins_") && key.contains("_top_") && key.endsWith("_name")) {
            String middle = key.substring("wins_".length(), key.length() - "_name".length());
            String[] split = middle.split("_top_");
            if (split.length != 2) {
                return "";
            }
            ChallengeType type = parseChallenge(split[0]);
            int rank = parseInt(split[1]);
            if (type == null || rank < 1) {
                return "";
            }
            VCompetitionPlugin.WinsEntry entry = plugin.getChallengeWinsTopAt(type, rank);
            return entry == null ? "none" : entry.name();
        }

        if (key.startsWith("wins_") && key.contains("_top_") && key.endsWith("_wins")) {
            String middle = key.substring("wins_".length(), key.length() - "_wins".length());
            String[] split = middle.split("_top_");
            if (split.length != 2) {
                return "0";
            }
            ChallengeType type = parseChallenge(split[0]);
            int rank = parseInt(split[1]);
            if (type == null || rank < 1) {
                return "0";
            }
            VCompetitionPlugin.WinsEntry entry = plugin.getChallengeWinsTopAt(type, rank);
            return entry == null ? "0" : String.valueOf(entry.wins());
        }

        if (player == null || player.getUniqueId() == null) {
            return "";
        }

        if (key.equals("player_position")) {
            int position = plugin.getPlayerPosition(player.getUniqueId());
            return position < 0 ? "0" : String.valueOf(position);
        }

        if (key.equals("player_points")) {
            return String.valueOf(plugin.getPlayerPoints(player.getUniqueId()));
        }

        if (key.equals("player_gap_up")) {
            return String.valueOf(plugin.getGapToAbove(player.getUniqueId()));
        }

        if (key.equals("player_gap_down")) {
            return String.valueOf(plugin.getGapToBelow(player.getUniqueId()));
        }

        if (key.equals("player_wins_total")) {
            return String.valueOf(plugin.getWinsTotal(player.getUniqueId()));
        }

        if (key.startsWith("player_wins_")) {
            String challengeRaw = key.substring("player_wins_".length());
            ChallengeType challengeType = parseChallenge(challengeRaw);
            if (challengeType == null) {
                return "0";
            }
            return String.valueOf(plugin.getWinsByChallenge(player.getUniqueId(), challengeType));
        }

        return null;
    }

    private int parseRank(String key, String prefix, String suffix) {
        String number = key.substring(prefix.length(), key.length() - suffix.length());
        return parseInt(number);
    }

    private int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private ChallengeType parseChallenge(String raw) {
        try {
            return ChallengeType.fromInput(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
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

    private String formatChallengePretty(ChallengeType type) {
        if (type == null) {
            return "ɴɪɴɢᴜɴᴏ";
        }
        return type.displayName();
    }
}
