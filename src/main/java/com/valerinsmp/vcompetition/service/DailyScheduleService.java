package com.valerinsmp.vcompetition.service;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import com.valerinsmp.vcompetition.model.ChallengeType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class DailyScheduleService {
    private static final String LAST_RANDOM_CHALLENGE_PATH = "schedule.last-random-challenge";

    private final VCompetitionPlugin plugin;
    private BukkitTask task;
    private Boolean lastInsideWindow;
    private boolean suppressAutoStartUntilWindowExit;

    // Cached config values — reloaded in start()
    private ZoneId cachedZoneId;
    private long cachedDurationMinutes;
    private List<int[]> cachedSlots; // each int[]{hour, minute}
    private String cachedChallenge;
    private boolean cachedStartOnBootstrap;

    public DailyScheduleService(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("schedule.enabled", true)) {
            return;
        }
        reloadCachedConfig(config);
        lastInsideWindow = null;
        suppressAutoStartUntilWindowExit = false;
        long interval = Math.max(20L, config.getLong("schedule.check-interval-ticks", 600L));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastInsideWindow = null;
        suppressAutoStartUntilWindowExit = false;
    }

    public void suppressAutoStartUntilWindowExit() {
        suppressAutoStartUntilWindowExit = true;
    }

    public long getCurrentOrNextSlotEndMillis() {
        ZonedDateTime now = ZonedDateTime.now(cachedZoneId != null ? cachedZoneId : ZoneId.of("America/Santiago"));
        Slot slot = currentSlot(now);
        if (slot != null) {
            return slot.end().toInstant().toEpochMilli();
        }
        long duration = cachedDurationMinutes > 0 ? cachedDurationMinutes : 30L;
        return now.plusMinutes(duration).toInstant().toEpochMilli();
    }

    private void tick() {
        try {
            ZonedDateTime now = ZonedDateTime.now(cachedZoneId);
            Slot slot = currentSlot(now);

            boolean bootstrapTick = lastInsideWindow == null;
            if (bootstrapTick) {
                lastInsideWindow = slot != null;
                if (slot == null) {
                    return;
                }
                if (!cachedStartOnBootstrap) {
                    suppressAutoStartUntilWindowExit = true;
                    return;
                }
            }

            if (slot != null) {
                if (suppressAutoStartUntilWindowExit && !plugin.hasActiveChallenge()) {
                    lastInsideWindow = true;
                    return;
                }
                if (!plugin.hasActiveChallenge()) {
                    long slotEndMillis = slot.end().toInstant().toEpochMilli();
                    plugin.startScheduledChallenge(readConfiguredChallenge(), slotEndMillis);
                }
                lastInsideWindow = true;
                return;
            }

            suppressAutoStartUntilWindowExit = false;
            if (plugin.isScheduleManagedChallengeActive()) {
                plugin.stopScheduledChallenge();
            }
            lastInsideWindow = false;
        } catch (Exception exception) {
            plugin.getLogger().warning("Error en DailyScheduleService: " + exception.getMessage());
        }
    }

    private Slot currentSlot(ZonedDateTime now) {
        if (cachedSlots == null || cachedSlots.isEmpty()) {
            return null;
        }
        for (int[] slot : cachedSlots) {
            ZonedDateTime slotStart = now.toLocalDate()
                .atTime(LocalTime.of(slot[0], slot[1]))
                .atZone(cachedZoneId);
            ZonedDateTime slotEnd = slotStart.plusMinutes(cachedDurationMinutes);
            if (!now.isBefore(slotStart) && now.isBefore(slotEnd)) {
                return new Slot(slotStart, slotEnd);
            }
        }
        return null;
    }

    private ChallengeType readConfiguredChallenge() {
        if (cachedChallenge != null && cachedChallenge.equalsIgnoreCase("RANDOM")) {
            return pickRandomChallengeNoRepeat();
        }
        try {
            return ChallengeType.fromInput(cachedChallenge == null ? "MINING" : cachedChallenge);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("schedule.challenge inválido: " + cachedChallenge + ". Usando MINING.");
            return ChallengeType.MINING;
        }
    }

    private ChallengeType pickRandomChallengeNoRepeat() {
        ChallengeType previous = readLastRandomChallenge();
        List<ChallengeType> pool = new ArrayList<>(ChallengeType.randomPool());
        pool.remove(previous);
        if (pool.isEmpty()) {
            pool = new ArrayList<>(ChallengeType.randomPool());
        }
        ChallengeType selected = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        // Write to config on async thread to avoid disk I/O on scheduler tick thread
        final String selectedName = selected.name();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getConfig().set(LAST_RANDOM_CHALLENGE_PATH, selectedName);
            plugin.saveConfig();
        });
        return selected;
    }

    private ChallengeType readLastRandomChallenge() {
        String raw = plugin.getConfig().getString(LAST_RANDOM_CHALLENGE_PATH, "");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ChallengeType.fromInput(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void reloadCachedConfig(FileConfiguration config) {
        try {
            cachedZoneId = ZoneId.of(config.getString("schedule.timezone", "America/Santiago"));
        } catch (Exception e) {
            plugin.getLogger().warning("Timezone inválido en config, usando America/Santiago");
            cachedZoneId = ZoneId.of("America/Santiago");
        }
        cachedDurationMinutes = Math.max(1L, config.getLong("challenge.duration-minutes", 30L));
        cachedChallenge = config.getString("schedule.challenge", "MINING");
        cachedStartOnBootstrap = config.getBoolean("schedule.start-on-bootstrap-if-inside-window", false);

        cachedSlots = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("schedule.slots")) {
            int hour   = clamp(toInt(entry.get("hour"),   0), 0, 23);
            int minute = clamp(toInt(entry.get("minute"), 0), 0, 59);
            cachedSlots.add(new int[]{hour, minute});
        }
    }

    private int toInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Slot(ZonedDateTime start, ZonedDateTime end) {}
}
