package com.valerinsmp.vcompetition.service;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.ChallengeType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

public final class WeeklyScheduleService {
    private final VCompetitionPlugin plugin;
    private BukkitTask task;
    private Boolean lastInsideWindow;

    public WeeklyScheduleService(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("schedule.enabled", true)) {
            return;
        }
        lastInsideWindow = null;
        long interval = Math.max(20L, config.getLong("schedule.check-interval-ticks", 600L));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastInsideWindow = null;
    }

    private void tick() {
        try {
            Window window = currentWindow();
            ZonedDateTime now = ZonedDateTime.now(window.zoneId);

            boolean inside = !now.isBefore(window.start) && now.isBefore(window.end);
            boolean startOnBootstrapInsideWindow = plugin.getConfig().getBoolean("schedule.start-on-bootstrap-if-inside-window", false);

            if (lastInsideWindow == null) {
                lastInsideWindow = inside;
                if (!inside) {
                    return;
                }
                if (!startOnBootstrapInsideWindow) {
                    return;
                }
            }

            if (inside) {
                if (!plugin.hasActiveChallenge() && !Boolean.TRUE.equals(lastInsideWindow)) {
                    plugin.startScheduledChallenge(readConfiguredChallenge());
                }
                lastInsideWindow = true;
                return;
            }

            if (plugin.isScheduleManagedChallengeActive()) {
                plugin.stopScheduledChallenge();
            }
            lastInsideWindow = false;
        } catch (Exception exception) {
            plugin.getLogger().warning("Error en WeeklyScheduleService: " + exception.getMessage());
        }
    }

    private ChallengeType readConfiguredChallenge() {
        String raw = plugin.getConfig().getString("schedule.challenge", "MINING");
        try {
            return ChallengeType.fromInput(raw == null ? "MINING" : raw);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("schedule.challenge inválido: " + raw + ". Usando MINING.");
            return ChallengeType.MINING;
        }
    }

    private Window currentWindow() {
        FileConfiguration config = plugin.getConfig();
        ZoneId zoneId = ZoneId.of(config.getString("schedule.timezone", "America/Santiago"));

        DayOfWeek startDay = DayOfWeek.valueOf(config.getString("schedule.start.day", "MONDAY").toUpperCase(Locale.ROOT));
        int startHour = clamp(config.getInt("schedule.start.hour", 18), 0, 23);
        int startMinute = clamp(config.getInt("schedule.start.minute", 0), 0, 59);

        DayOfWeek endDay = DayOfWeek.valueOf(config.getString("schedule.end.day", "SUNDAY").toUpperCase(Locale.ROOT));
        int endHour = clamp(config.getInt("schedule.end.hour", 22), 0, 23);
        int endMinute = clamp(config.getInt("schedule.end.minute", 0), 0, 59);

        ZonedDateTime now = ZonedDateTime.now(zoneId);

        LocalDate weekStartDate = now.toLocalDate();
        while (weekStartDate.getDayOfWeek() != startDay) {
            weekStartDate = weekStartDate.minusDays(1);
        }

        ZonedDateTime start = ZonedDateTime.of(LocalDateTime.of(weekStartDate, LocalTime.of(startHour, startMinute)), zoneId);

        LocalDate weekEndDate = weekStartDate;
        while (weekEndDate.getDayOfWeek() != endDay) {
            weekEndDate = weekEndDate.plusDays(1);
        }
        ZonedDateTime end = ZonedDateTime.of(LocalDateTime.of(weekEndDate, LocalTime.of(endHour, endMinute)), zoneId);
        if (!end.isAfter(start)) {
            end = end.plusWeeks(1);
        }

        if (now.isBefore(start)) {
            start = start.minusWeeks(1);
            end = end.minusWeeks(1);
        }

        return new Window(zoneId, start, end);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Window(ZoneId zoneId, ZonedDateTime start, ZonedDateTime end) {
    }
}
