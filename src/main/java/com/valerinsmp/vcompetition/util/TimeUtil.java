package com.valerinsmp.vcompetition.util;

public final class TimeUtil {
    private TimeUtil() {}

    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1_000L);
        long days    = totalSeconds / 86_400L;
        long hours   = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L)  / 60L;
        long seconds = totalSeconds % 60L;
        if (days    > 0) return days    + "d " + hours   + "h";
        if (hours   > 0) return hours   + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }
}
