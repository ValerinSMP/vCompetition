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

/**
 * Slot-aware schedule service.  Instantiate once per competition slot:
 *   new DailyScheduleService(plugin, "daily",   "schedule")
 *   new DailyScheduleService(plugin, "special",  "special-event")
 *
 * Config keys read:  &lt;configPrefix&gt;.enabled, .timezone, .challenge,
 *   .slots[], .check-interval-ticks, .start-on-bootstrap-if-inside-window,
 *   .duration-minutes (falls back to challenge.duration-minutes).
 */
public final class DailyScheduleService {
    private static final String LAST_RANDOM_CHALLENGE_PATH_SUFFIX = ".last-random-challenge";

    private final VCompetitionPlugin plugin;
    private final String slotId;
    private final String configPrefix;

    private BukkitTask task;
    private Boolean lastInsideWindow;
    private boolean suppressAutoStartUntilWindowExit;

    // Cached config values — refreshed in start()
    private ZoneId cachedZoneId;
    private long cachedDurationMinutes;
    private List<int[]> cachedSlots;
    private String cachedChallenge;
    private boolean cachedStartOnBootstrap;

    public DailyScheduleService(VCompetitionPlugin plugin, String slotId, String configPrefix) {
        this.plugin = plugin;
        this.slotId = slotId;
        this.configPrefix = configPrefix;
    }

    public void start() {
        stop();
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean(configPrefix + ".enabled", true)) {
            return;
        }
        reloadCachedConfig(config);
        lastInsideWindow = null;
        suppressAutoStartUntilWindowExit = false;
        long interval = Math.max(20L, config.getLong(configPrefix + ".check-interval-ticks", 600L));
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
        ZoneId zone = cachedZoneId != null ? cachedZoneId : ZoneId.of("America/Santiago");
        ZonedDateTime now = ZonedDateTime.now(zone);
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
                if (slot == null) return;
                if (!cachedStartOnBootstrap) {
                    suppressAutoStartUntilWindowExit = true;
                    return;
                }
            }

            if (slot != null) {
                if (suppressAutoStartUntilWindowExit && !plugin.hasActiveChallenge(slotId)) {
                    lastInsideWindow = true;
                    return;
                }
                if (!plugin.hasActiveChallenge(slotId)) {
                    long slotEndMillis = slot.end().toInstant().toEpochMilli();
                    plugin.startScheduledChallenge(slotId, readConfiguredChallenge(), slotEndMillis);
                }
                lastInsideWindow = true;
                return;
            }

            suppressAutoStartUntilWindowExit = false;
            if (plugin.isScheduleManagedChallengeActive(slotId)) {
                plugin.stopScheduledChallenge(slotId);
            }
            lastInsideWindow = false;
        } catch (Exception exception) {
            plugin.getLogger().warning("[" + slotId + "] Error en DailyScheduleService: " + exception.getMessage());
        }
    }

    private Slot currentSlot(ZonedDateTime now) {
        if (cachedSlots == null || cachedSlots.isEmpty()) return null;
        // Also check yesterday's start so slots that cross midnight (e.g. 23:30 + 60min) are
        // still detected right after 00:00, when "today" would otherwise compute a start in the future.
        for (int dayOffset = 0; dayOffset >= -1; dayOffset--) {
            for (int[] s : cachedSlots) {
                ZonedDateTime slotStart = now.toLocalDate().plusDays(dayOffset)
                        .atTime(LocalTime.of(s[0], s[1]))
                        .atZone(cachedZoneId);
                ZonedDateTime slotEnd = slotStart.plusMinutes(cachedDurationMinutes);
                if (!now.isBefore(slotStart) && now.isBefore(slotEnd)) {
                    return new Slot(slotStart, slotEnd);
                }
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
            plugin.getLogger().warning(configPrefix + ".challenge inválido: " + cachedChallenge + ". Usando MINING.");
            return ChallengeType.MINING;
        }
    }

    private ChallengeType pickRandomChallengeNoRepeat() {
        ChallengeType previous = readLastRandomChallenge();
        List<ChallengeType> pool = new ArrayList<>(ChallengeType.randomPool());
        pool.remove(previous);
        if (pool.isEmpty()) pool = new ArrayList<>(ChallengeType.randomPool());
        ChallengeType selected = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        // getConfig().set() must run on the main thread; saveConfig() (disk I/O) goes async.
        plugin.getConfig().set(configPrefix + LAST_RANDOM_CHALLENGE_PATH_SUFFIX, selected.name());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, plugin::saveConfig);
        return selected;
    }

    private ChallengeType readLastRandomChallenge() {
        String raw = plugin.getConfig().getString(configPrefix + LAST_RANDOM_CHALLENGE_PATH_SUFFIX, "");
        if (raw == null || raw.isBlank()) return null;
        try {
            return ChallengeType.fromInput(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void reloadCachedConfig(FileConfiguration config) {
        try {
            cachedZoneId = ZoneId.of(config.getString(configPrefix + ".timezone", "America/Santiago"));
        } catch (Exception e) {
            plugin.getLogger().warning("Timezone inválido en " + configPrefix + ", usando America/Santiago");
            cachedZoneId = ZoneId.of("America/Santiago");
        }
        // Allow per-slot duration override; fall back to global challenge.duration-minutes
        long globalDuration = config.getLong("challenge.duration-minutes", 30L);
        cachedDurationMinutes = Math.max(1L, config.getLong(configPrefix + ".duration-minutes", globalDuration));
        cachedChallenge       = config.getString(configPrefix + ".challenge", "MINING");
        cachedStartOnBootstrap = config.getBoolean(configPrefix + ".start-on-bootstrap-if-inside-window", false);

        cachedSlots = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList(configPrefix + ".slots")) {
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
