package com.valerinsmp.vcompetition.service;

import com.valerinsmp.vcompetition.VCompetitionPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

public final class FancyNpcSkinRefreshService {
    private final VCompetitionPlugin plugin;
    private final Map<String, String> appliedSkinCache = new ConcurrentHashMap<>();
    private final Map<String, Long> invalidUsernameRetryAt = new ConcurrentHashMap<>();

    private BukkitTask refreshTask;
    private NpcBridge bridge;
    private volatile long invalidUsernameRetryMillis = 1800_000L;

    public FancyNpcSkinRefreshService(VCompetitionPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();

        if (!plugin.getConfig().getBoolean("fancy-npcs.skin-refresh.enabled", false)) {
            return;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")) {
            plugin.getLogger().warning("Skin refresh habilitado, pero FancyNpcs no está activo.");
            return;
        }

        bridge = NpcBridge.create(plugin);
        if (bridge == null) {
            plugin.getLogger().warning("No se pudo inicializar bridge de FancyNpcs para auto-refresh de skins.");
            return;
        }

        long initialDelayTicks = Math.max(20L, plugin.getConfig().getLong("fancy-npcs.skin-refresh.initial-delay-seconds", 20L) * 20L);
        long intervalTicks = Math.max(20L, plugin.getConfig().getLong("fancy-npcs.skin-refresh.interval-seconds", 900L) * 20L);

        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runRefreshCycle, initialDelayTicks, intervalTicks);
        plugin.getLogger().info("Auto-refresh de skins FancyNpcs habilitado.");
    }

    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        appliedSkinCache.clear();
        invalidUsernameRetryAt.clear();
        bridge = null;
    }

    public boolean forceRefreshNow() {
        if (!plugin.getConfig().getBoolean("fancy-npcs.skin-refresh.enabled", false)) {
            return false;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")) {
            return false;
        }
        if (bridge == null) {
            bridge = NpcBridge.create(plugin);
            if (bridge == null) {
                return false;
            }
        }

        appliedSkinCache.clear();
        runRefreshCycle();
        return true;
    }

    private void runRefreshCycle() {
        if (bridge == null) {
            return;
        }

        RefreshMode mode = loadMode();
        boolean debug = isDebugEnabled();
        invalidUsernameRetryMillis = Math.max(60_000L,
            plugin.getConfig().getLong("fancy-npcs.skin-refresh.invalid-username-retry-seconds", 1800L) * 1000L);
        for (int rank = 1; rank <= 3; rank++) {
            String npcName = loadTopNpcName(rank);
            if (npcName.isEmpty()) {
                if (debug) {
                    plugin.getLogger().info("[FancyNpcs] top" + rank + " sin nombre de NPC configurado.");
                }
                continue;
            }

            VCompetitionPlugin.RankingEntry entry = plugin.getCurrentTopAt(rank);
            String targetSkin = null;
            boolean fromRankingName = false;
            boolean likelyBedrockName = false;
            if (entry != null && entry.name() != null && !entry.name().isBlank()) {
                targetSkin = entry.name();
                fromRankingName = true;
                likelyBedrockName = isLikelyBedrockGamertag(targetSkin);
            }

            boolean forceFallbackForRankingName = fromRankingName
                    && !Bukkit.getOnlineMode()
                    && plugin.getConfig().getBoolean("fancy-npcs.skin-refresh.force-fallback-when-offline-mode", false);

            if (targetSkin == null || targetSkin.isBlank()) {
                targetSkin = loadFallbackSkin(rank);
                if (targetSkin.isBlank()) {
                    if (debug) {
                        plugin.getLogger().info("[FancyNpcs] sin datos de ranking ni fallback para top" + rank + ".");
                    }
                    continue;
                }

                if (isPlaceholderFallbackSkin(targetSkin)) {
                    if (debug) {
                        plugin.getLogger().warning("[FancyNpcs] fallback skin inválido para top" + rank + ": " + targetSkin + " (reemplaza la URL/id de ejemplo en config)");
                    }
                    continue;
                }
            }

            if (fromRankingName) {
                if (forceFallbackForRankingName || likelyBedrockName) {
                    String fallback = resolveInvalidUsernameFallback(rank, targetSkin);
                    if (fallback.isBlank()) {
                        if (debug) {
                            String reason = forceFallbackForRankingName ? "offline-mode" : "gamertag bedrock";
                            plugin.getLogger().warning("[FancyNpcs] " + reason + " en top" + rank + " sin fallback válido: '" + targetSkin + "'");
                        }
                        continue;
                    }
                    if (debug) {
                        String reason = forceFallbackForRankingName ? "offline-mode" : "gamertag bedrock";
                        plugin.getLogger().info("[FancyNpcs] " + reason + " detectado en top" + rank + " ('" + targetSkin + "'), usando fallback directo: " + fallback);
                    }
                    targetSkin = fallback;
                }

                String normalized = normalizePlayerSkinIdentifier(targetSkin);
                if (normalized.isBlank()) {
                    String fallback = loadFallbackSkin(rank);
                    if (fallback.isBlank() || isPlaceholderFallbackSkin(fallback)) {
                        if (debug) {
                            plugin.getLogger().warning("[FancyNpcs] nombre de jugador inválido para skin en top" + rank + ": '" + targetSkin + "' (sin fallback válido)");
                        }
                        continue;
                    }
                    targetSkin = fallback;
                } else {
                    if (!normalized.equals(targetSkin) && debug) {
                        plugin.getLogger().info("[FancyNpcs] normalizando skin de top" + rank + " de '" + targetSkin + "' a '" + normalized + "'.");
                    }
                    targetSkin = normalized;
                }
            }

            if (targetSkin.isBlank()) {
                continue;
            }

            long now = System.currentTimeMillis();
            String skinRetryKey = targetSkin.toLowerCase(Locale.ROOT);
            Long retryAt = invalidUsernameRetryAt.get(skinRetryKey);
            if (retryAt != null && now < retryAt) {
                if (debug) {
                    plugin.getLogger().info("[FancyNpcs] saltando skin temporalmente inválida: " + targetSkin);
                }
                continue;
            }

            String cacheKey = npcName.toLowerCase(Locale.ROOT);
            String current = appliedSkinCache.get(cacheKey);
            if (targetSkin.equalsIgnoreCase(current)) {
                if (debug) {
                    plugin.getLogger().info("[FancyNpcs] NPC " + npcName + " ya tiene skin objetivo: " + targetSkin);
                }
                continue;
            }

            try {
                boolean applied = bridge.applySkin(npcName, targetSkin, mode == RefreshMode.RESPAWN);
                if (!applied) {
                    plugin.getLogger().warning("No se encontró NPC FancyNpcs: " + npcName);
                    continue;
                }
                appliedSkinCache.put(cacheKey, targetSkin);
                if (debug) {
                    plugin.getLogger().info("[FancyNpcs] NPC " + npcName + " actualizado a skin " + targetSkin + " (top" + rank + ").");
                }
            } catch (Exception exception) {
                Throwable root = rootCause(exception);
                if (isInvalidUsernameSkinException(root)) {
                    invalidUsernameRetryAt.put(skinRetryKey, System.currentTimeMillis() + invalidUsernameRetryMillis);
                    String fallback = resolveInvalidUsernameFallback(rank, targetSkin);
                    if (!fallback.isBlank() && !fallback.equalsIgnoreCase(targetSkin)) {
                        try {
                            boolean fallbackApplied = bridge.applySkin(npcName, fallback, mode == RefreshMode.RESPAWN);
                            if (fallbackApplied) {
                                appliedSkinCache.put(cacheKey, fallback);
                                if (debug) {
                                    plugin.getLogger().info("[FancyNpcs] skin fallback aplicada para NPC " + npcName + ": " + fallback);
                                }
                                continue;
                            }
                        } catch (Exception fallbackException) {
                            plugin.getLogger().warning("Error aplicando fallback skin de NPC '" + npcName + "': " + summarizeException(fallbackException));
                            if (debug) {
                                fallbackException.printStackTrace();
                            }
                        }
                    }
                    plugin.getLogger().warning("Skin inválida/no existente para NPC '" + npcName + "': " + targetSkin + " (reintento en " + (invalidUsernameRetryMillis / 1000L) + "s)");
                    if (debug) {
                        plugin.getLogger().warning("[FancyNpcs] skin objetivo: " + targetSkin + " | modo=" + mode);
                    }
                    continue;
                }

                plugin.getLogger().warning("Error refrescando skin de NPC '" + npcName + "': " + summarizeException(exception));
                if (debug) {
                    plugin.getLogger().warning("[FancyNpcs] skin objetivo: " + targetSkin + " | modo=" + mode);
                    exception.printStackTrace();
                }
            }
        }
    }

    private boolean isPlaceholderFallbackSkin(String skin) {
        String normalized = skin.toLowerCase(Locale.ROOT);
        return normalized.contains("reemplaza")
                || normalized.endsWith("/texture/")
                || normalized.endsWith("/texture")
                || normalized.contains("texture_id");
    }

    private String summarizeException(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String type = root.getClass().getSimpleName();
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        return type + ": " + message;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private boolean isInvalidUsernameSkinException(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String type = throwable.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String message = throwable.getMessage() == null ? "" : throwable.getMessage().toLowerCase(Locale.ROOT);
        return type.contains("skinloadexception") && message.contains("username");
    }

    private boolean isLikelyBedrockGamertag(String input) {
        if (input == null) {
            return false;
        }
        String value = input.trim();
        return !value.isEmpty() && value.charAt(0) == '.';
    }

    private String resolveInvalidUsernameFallback(int rank, String targetSkin) {
        String configured = loadFallbackSkin(rank);
        if (!configured.isBlank()) {
            return configured;
        }

        List<String> options = plugin.getConfig().getStringList("fancy-npcs.skin-refresh.invalid-username-fallback-skins");
        if (options == null || options.isEmpty()) {
            options = List.of("Steve", "Alex");
        }

        List<String> cleaned = options.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .toList();
        if (cleaned.isEmpty()) {
            cleaned = List.of("Steve", "Alex");
        }

        if (cleaned.size() == 1) {
            return cleaned.get(0);
        }

        int index = ThreadLocalRandom.current().nextInt(cleaned.size());
        String selected = cleaned.get(index);
        if (selected.equalsIgnoreCase(targetSkin)) {
            int alternative = (index + 1) % cleaned.size();
            return cleaned.get(alternative);
        }
        return selected;
    }

    private String normalizePlayerSkinIdentifier(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        int start = 0;
        while (start < trimmed.length()) {
            char ch = trimmed.charAt(start);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_') {
                break;
            }
            start++;
        }
        if (start >= trimmed.length()) {
            return "";
        }

        String cleaned = trimmed.substring(start);
        StringBuilder builder = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_') {
                builder.append(ch);
            }
        }

        String normalized = builder.toString();
        if (normalized.length() < 3) {
            return "";
        }
        if (normalized.length() > 16) {
            normalized = normalized.substring(0, 16);
        }
        return normalized;
    }

    private String loadTopNpcName(int rank) {
        return plugin.getConfig().getString("fancy-npcs.skin-refresh.top-npcs.top" + rank, "").trim();
    }

    private String loadFallbackSkin(int rank) {
        String global = plugin.getConfig().getString("fancy-npcs.skin-refresh.fallback-skins.global", "").trim();
        if (!global.isEmpty() && isUsableFallbackIdentifier(global)) {
            return global;
        }
        String perTop = plugin.getConfig().getString("fancy-npcs.skin-refresh.fallback-skins.top" + rank, "").trim();
        return isUsableFallbackIdentifier(perTop) ? perTop : "";
    }

    private boolean isUsableFallbackIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (isPlaceholderFallbackSkin(value)) {
            return false;
        }

        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return lower.contains("textures.minecraft.net/texture/");
        }

        return !normalizePlayerSkinIdentifier(trimmed).isBlank();
    }

    private boolean isDebugEnabled() {
        return plugin.getConfig().getBoolean("fancy-npcs.skin-refresh.debug-logs", false);
    }

    private RefreshMode loadMode() {
        String raw = plugin.getConfig().getString("fancy-npcs.skin-refresh.mode", "UPDATE");
        try {
            return RefreshMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return RefreshMode.UPDATE;
        }
    }

    private enum RefreshMode {
        UPDATE,
        RESPAWN
    }

    private static final class NpcBridge {
        private final Object fancyApi;
        private final Method getNpcManagerMethod;
        private final Method getNpcByNameMethod;
        private final Method getDataMethod;
        private final Method setSkinMethod;
        private final Method updateForAllMethod;
        private final Method removeForAllMethod;
        private final Method spawnForAllMethod;

        private NpcBridge(
                Object fancyApi,
                Method getNpcManagerMethod,
                Method getNpcByNameMethod,
                Method getDataMethod,
                Method setSkinMethod,
                Method updateForAllMethod,
                Method removeForAllMethod,
                Method spawnForAllMethod
        ) {
            this.fancyApi = fancyApi;
            this.getNpcManagerMethod = getNpcManagerMethod;
            this.getNpcByNameMethod = getNpcByNameMethod;
            this.getDataMethod = getDataMethod;
            this.setSkinMethod = setSkinMethod;
            this.updateForAllMethod = updateForAllMethod;
            this.removeForAllMethod = removeForAllMethod;
            this.spawnForAllMethod = spawnForAllMethod;
        }

        static NpcBridge create(VCompetitionPlugin plugin) {
            try {
                Object fancyApi;
                Method getNpcManagerMethod;

                Plugin fancyPlugin = Bukkit.getPluginManager().getPlugin("FancyNpcs");
                if (fancyPlugin != null) {
                    Method pluginMethod = findMethodByName(fancyPlugin.getClass(), "getNpcManager");
                    if (pluginMethod != null) {
                        fancyApi = fancyPlugin;
                        getNpcManagerMethod = pluginMethod;
                    } else {
                        ResolvedApi fallback = resolveStaticApi();
                        fancyApi = fallback.api();
                        getNpcManagerMethod = fallback.getNpcManagerMethod();
                    }
                } else {
                    ResolvedApi fallback = resolveStaticApi();
                    fancyApi = fallback.api();
                    getNpcManagerMethod = fallback.getNpcManagerMethod();
                }

                Object manager = getNpcManagerMethod.invoke(fancyApi);
                Class<?> managerClass = manager.getClass();
                Method getNpcByNameMethod = findMethodByNameAndParam(managerClass, "getNpc", String.class);
                if (getNpcByNameMethod == null) {
                    throw new NoSuchMethodException("No se encontró método getNpc(String) en manager " + managerClass.getName());
                }

                Class<?> npcClass = getNpcByNameMethod.getReturnType();
                Method getDataMethod = npcClass.getMethod("getData");
                Method updateForAllMethod = npcClass.getMethod("updateForAll");
                Method removeForAllMethod = npcClass.getMethod("removeForAll");
                Method spawnForAllMethod = npcClass.getMethod("spawnForAll");

                Class<?> dataClass = getDataMethod.getReturnType();
                Method setSkinMethod = dataClass.getMethod("setSkin", String.class);

                return new NpcBridge(
                        fancyApi,
                        getNpcManagerMethod,
                        getNpcByNameMethod,
                        getDataMethod,
                        setSkinMethod,
                        updateForAllMethod,
                        removeForAllMethod,
                        spawnForAllMethod
                );
            } catch (Exception exception) {
                plugin.getLogger().warning("Bridge FancyNpcs falló: " + exception.getMessage());
                return null;
            }
        }

        private static ResolvedApi resolveStaticApi() throws Exception {
            String[] classNames = {
                    "de.oliver.fancynpcs.FancyNpcsPlugin",
                    "de.oliver.fancynpcs.api.FancyNpcsPlugin"
            };
            for (String className : classNames) {
                try {
                    Class<?> pluginClass = Class.forName(className);
                    Method getMethod = pluginClass.getMethod("get");
                    Object api = getMethod.invoke(null);
                    Method managerMethod = findMethodByName(pluginClass, "getNpcManager");
                    if (managerMethod != null) {
                        return new ResolvedApi(api, managerMethod);
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }
            throw new ClassNotFoundException("No se encontró entrypoint estático compatible de FancyNpcs");
        }

        private static Method findMethodByName(Class<?> type, String name) {
            return Arrays.stream(type.getMethods())
                    .filter(method -> method.getName().equals(name) && method.getParameterCount() == 0)
                    .findFirst()
                    .orElse(null);
        }

        private static Method findMethodByNameAndParam(Class<?> type, String name, Class<?> paramType) {
            return Arrays.stream(type.getMethods())
                    .filter(method -> method.getName().equals(name)
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].equals(paramType))
                    .findFirst()
                    .orElse(null);
        }

        private record ResolvedApi(Object api, Method getNpcManagerMethod) {
        }

        boolean applySkin(String npcName, String skin, boolean respawn) throws Exception {
            Object manager = getNpcManagerMethod.invoke(fancyApi);
            Object npc = getNpcByNameMethod.invoke(manager, npcName);
            if (npc == null) {
                return false;
            }

            Object data = getDataMethod.invoke(npc);
            setSkinMethod.invoke(data, skin);

            if (respawn) {
                removeForAllMethod.invoke(npc);
                spawnForAllMethod.invoke(npc);
            } else {
                updateForAllMethod.invoke(npc);
            }
            return true;
        }
    }
}
