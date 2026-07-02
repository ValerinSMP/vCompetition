package com.valerinsmp.vcompetition.storage;

import com.valerinsmp.vcompetition.model.BlockKey;
import com.valerinsmp.vcompetition.model.ChallengeType;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class SQLiteManager {
    private final JavaPlugin plugin;
    private final ThreadPoolExecutor executor;
    private final Set<CompletableFuture<?>> pendingTasks = ConcurrentHashMap.newKeySet();
    private Connection connection;

    public SQLiteManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "vCompetition-SQLite");
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    public void connect() throws SQLException {
        // Explicit registration (rather than relying on the driver's static init / SPI
        // auto-loading) ensures the driver is associated with THIS classloader context —
        // required for PlugMan reload compatibility, and for MockBukkit test classloaders,
        // where DriverManager's caller-classloader check otherwise rejects an already-loaded
        // driver registered under a different loader. Falls back to the unshaded class name
        // so tests (running against target/classes, pre-shading) work too.
        try {
            Class<?> driverClass;
            try {
                driverClass = Class.forName("com.valerinsmp.vcompetition.libs.sqlite.JDBC");
            } catch (ClassNotFoundException shaded) {
                driverClass = Class.forName("org.sqlite.JDBC");
            }
            DriverManager.registerDriver((java.sql.Driver) driverClass.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException exception) {
            throw new SQLException("No se encontró el driver SQLite (ni shadeado ni plano)", exception);
        }
        File dbFile = new File(plugin.getDataFolder(), "competition.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA synchronous=NORMAL;");

            // Legacy single-row table — kept for migration only
            statement.execute("CREATE TABLE IF NOT EXISTS challenge_state ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1),"
                    + "challenge_type TEXT,"
                    + "start_time INTEGER,"
                    + "end_time INTEGER,"
                    + "active INTEGER NOT NULL DEFAULT 0"
                    + ")");

            // Multi-slot challenge state (one row per slot: 'daily', 'special', …)
            statement.execute("CREATE TABLE IF NOT EXISTS challenge_state_v2 ("
                    + "slot_id TEXT PRIMARY KEY,"
                    + "challenge_type TEXT,"
                    + "start_time INTEGER NOT NULL DEFAULT 0,"
                    + "end_time INTEGER NOT NULL DEFAULT 0,"
                    + "active INTEGER NOT NULL DEFAULT 0"
                    + ")");

            // Migrate legacy row to slot 'daily'
            statement.execute("INSERT OR IGNORE INTO challenge_state_v2 (slot_id, challenge_type, start_time, end_time, active) "
                    + "SELECT 'daily', challenge_type, start_time, end_time, active "
                    + "FROM challenge_state WHERE id = 1");

            // Legacy progress table — kept for migration only
            statement.execute("CREATE TABLE IF NOT EXISTS player_progress ("
                    + "challenge_type TEXT NOT NULL,"
                    + "player_uuid TEXT NOT NULL,"
                    + "player_name TEXT NOT NULL,"
                    + "points INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (challenge_type, player_uuid)"
                    + ")");

            // Multi-slot progress (slot_id distinguishes daily vs special)
            statement.execute("CREATE TABLE IF NOT EXISTS player_progress_v2 ("
                    + "slot_id TEXT NOT NULL,"
                    + "challenge_type TEXT NOT NULL,"
                    + "player_uuid TEXT NOT NULL,"
                    + "player_name TEXT NOT NULL,"
                    + "points INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (slot_id, challenge_type, player_uuid)"
                    + ")");

            // Migrate legacy progress rows to slot 'daily'
            statement.execute("INSERT OR IGNORE INTO player_progress_v2 (slot_id, challenge_type, player_uuid, player_name, points) "
                    + "SELECT 'daily', challenge_type, player_uuid, player_name, points FROM player_progress");

            statement.execute("CREATE TABLE IF NOT EXISTS placed_blocks ("
                    + "world TEXT NOT NULL,"
                    + "x INTEGER NOT NULL,"
                    + "y INTEGER NOT NULL,"
                    + "z INTEGER NOT NULL,"
                    + "PRIMARY KEY (world, x, y, z)"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS challenge_results ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "challenge_type TEXT NOT NULL,"
                    + "winner_uuid TEXT NOT NULL,"
                    + "winner_name TEXT NOT NULL,"
                    + "points INTEGER NOT NULL,"
                    + "ended_at INTEGER NOT NULL"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS player_wins_total ("
                    + "player_uuid TEXT PRIMARY KEY,"
                    + "player_name TEXT NOT NULL,"
                    + "wins INTEGER NOT NULL DEFAULT 0"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS player_wins_challenge ("
                    + "challenge_type TEXT NOT NULL,"
                    + "player_uuid TEXT NOT NULL,"
                    + "player_name TEXT NOT NULL,"
                    + "wins INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (challenge_type, player_uuid)"
                    + ")");
        }
    }

    // ── Challenge state ──────────────────────────────────────────────────────

    public CompletableFuture<Void> saveChallengeState(String slotId, ChallengeType challengeType,
                                                       long startTime, long endTime, boolean active) {
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO challenge_state_v2 (slot_id, challenge_type, start_time, end_time, active) "
                    + "VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT(slot_id) DO UPDATE SET challenge_type = excluded.challenge_type, "
                    + "start_time = excluded.start_time, end_time = excluded.end_time, active = excluded.active";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, slotId);
                statement.setString(2, challengeType == null ? null : challengeType.name());
                statement.setLong(3, startTime);
                statement.setLong(4, endTime);
                statement.setInt(5, active ? 1 : 0);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo guardar challenge_state_v2", exception);
            }
        }, executor);
        return track(task);
    }

    public CompletableFuture<ChallengeStateSnapshot> loadChallengeState(String slotId) {
        CompletableFuture<ChallengeStateSnapshot> task = CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT challenge_type, start_time, end_time, active FROM challenge_state_v2 WHERE slot_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, slotId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return new ChallengeStateSnapshot(null, 0L, 0L, false);
                    }
                    String challengeName = resultSet.getString("challenge_type");
                    ChallengeType challengeType = challengeName == null ? null : ChallengeType.valueOf(challengeName);
                    return new ChallengeStateSnapshot(
                            challengeType,
                            resultSet.getLong("start_time"),
                            resultSet.getLong("end_time"),
                            resultSet.getInt("active") == 1
                    );
                }
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo cargar challenge_state_v2 para slot " + slotId, exception);
            }
        }, executor);
        return track(task);
    }

    // ── Player progress ──────────────────────────────────────────────────────

    public CompletableFuture<Void> clearProgress(String slotId, ChallengeType challengeType) {
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM player_progress_v2 WHERE slot_id = ? AND challenge_type = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, slotId);
                statement.setString(2, challengeType.name());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo limpiar progreso", exception);
            }
        }, executor);
        return track(task);
    }

    public CompletableFuture<Map<UUID, PlayerScore>> loadProgress(String slotId, ChallengeType challengeType) {
        CompletableFuture<Map<UUID, PlayerScore>> task = CompletableFuture.supplyAsync(() -> {
            Map<UUID, PlayerScore> data = new HashMap<>();
            String sql = "SELECT player_uuid, player_name, points FROM player_progress_v2 WHERE slot_id = ? AND challenge_type = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, slotId);
                statement.setString(2, challengeType.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        UUID uuid = UUID.fromString(resultSet.getString("player_uuid"));
                        String name = resultSet.getString("player_name");
                        int points = resultSet.getInt("points");
                        data.put(uuid, new PlayerScore(name, points));
                    }
                }
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo cargar progreso", exception);
            }
            return data;
        }, executor);
        return track(task);
    }

    public CompletableFuture<Void> batchUpsertPlayerScores(String slotId, ChallengeType challengeType,
                                                            Map<UUID, Integer> scores, Map<UUID, String> names) {
        if (scores.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<UUID, Integer> snapshot = new java.util.HashMap<>(scores);
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_progress_v2 (slot_id, challenge_type, player_uuid, player_name, points) "
                    + "VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT(slot_id, challenge_type, player_uuid) DO UPDATE SET player_name = excluded.player_name, points = excluded.points";
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (Map.Entry<UUID, Integer> entry : snapshot.entrySet()) {
                        UUID uuid = entry.getKey();
                        statement.setString(1, slotId);
                        statement.setString(2, challengeType.name());
                        statement.setString(3, uuid.toString());
                        statement.setString(4, names.getOrDefault(uuid, "Unknown"));
                        statement.setInt(5, entry.getValue());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("No se pudo guardar batch de scores", exception);
            } finally {
                try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        }, executor);
        return track(task);
    }

    // ── Placed blocks ────────────────────────────────────────────────────────

    public CompletableFuture<List<BlockKey>> loadPlacedBlocks() {
        CompletableFuture<List<BlockKey>> task = CompletableFuture.supplyAsync(() -> {
            List<BlockKey> blocks = new ArrayList<>();
            String sql = "SELECT world, x, y, z FROM placed_blocks";
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    blocks.add(new BlockKey(
                            resultSet.getString("world"),
                            resultSet.getInt("x"),
                            resultSet.getInt("y"),
                            resultSet.getInt("z")
                    ));
                }
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo cargar placed_blocks", exception);
            }
            return blocks;
        }, executor);
        return track(task);
    }

    public CompletableFuture<Void> batchAddPlacedBlocks(Set<BlockKey> blocks) {
        if (blocks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Set<BlockKey> snapshot = new java.util.HashSet<>(blocks);
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR IGNORE INTO placed_blocks(world, x, y, z) VALUES (?, ?, ?, ?)";
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (BlockKey key : snapshot) {
                        statement.setString(1, key.world());
                        statement.setInt(2, key.x());
                        statement.setInt(3, key.y());
                        statement.setInt(4, key.z());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("No se pudo batch-insertar placed_blocks", exception);
            } finally {
                try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        }, executor);
        return track(task);
    }

    public CompletableFuture<Void> batchRemovePlacedBlocks(Set<BlockKey> blocks) {
        if (blocks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Set<BlockKey> snapshot = new java.util.HashSet<>(blocks);
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?";
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (BlockKey key : snapshot) {
                        statement.setString(1, key.world());
                        statement.setInt(2, key.x());
                        statement.setInt(3, key.y());
                        statement.setInt(4, key.z());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("No se pudo batch-eliminar placed_blocks", exception);
            } finally {
                try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        }, executor);
        return track(task);
    }

    public CompletableFuture<Void> clearPlacedBlocks() {
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM placed_blocks");
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo limpiar placed_blocks", exception);
            }
        }, executor);
        return track(task);
    }

    // ── Winners & win history ────────────────────────────────────────────────

    public CompletableFuture<Void> recordWinner(ChallengeType challengeType, UUID playerUuid,
                                                 String playerName, int points, long endedAt) {
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try {
                connection.setAutoCommit(false);

                String insertResult = "INSERT INTO challenge_results (challenge_type, winner_uuid, winner_name, points, ended_at) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(insertResult)) {
                    statement.setString(1, challengeType.name());
                    statement.setString(2, playerUuid.toString());
                    statement.setString(3, playerName);
                    statement.setInt(4, points);
                    statement.setLong(5, endedAt);
                    statement.executeUpdate();
                }

                String upsertTotal = "INSERT INTO player_wins_total(player_uuid, player_name, wins) VALUES(?, ?, 1) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, wins = player_wins_total.wins + 1";
                try (PreparedStatement statement = connection.prepareStatement(upsertTotal)) {
                    statement.setString(1, playerUuid.toString());
                    statement.setString(2, playerName);
                    statement.executeUpdate();
                }

                String upsertByChallenge = "INSERT INTO player_wins_challenge(challenge_type, player_uuid, player_name, wins) VALUES(?, ?, ?, 1) "
                        + "ON CONFLICT(challenge_type, player_uuid) DO UPDATE SET player_name = excluded.player_name, wins = player_wins_challenge.wins + 1";
                try (PreparedStatement statement = connection.prepareStatement(upsertByChallenge)) {
                    statement.setString(1, challengeType.name());
                    statement.setString(2, playerUuid.toString());
                    statement.setString(3, playerName);
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (SQLException exception) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw new RuntimeException("No se pudo registrar ganador", exception);
            } finally {
                try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        }, executor);
        return track(task);
    }

    public CompletableFuture<Map<UUID, PlayerWins>> loadWinsTotal() {
        CompletableFuture<Map<UUID, PlayerWins>> task = CompletableFuture.supplyAsync(() -> {
            Map<UUID, PlayerWins> wins = new HashMap<>();
            String sql = "SELECT player_uuid, player_name, wins FROM player_wins_total";
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    UUID uuid = UUID.fromString(resultSet.getString("player_uuid"));
                    wins.put(uuid, new PlayerWins(resultSet.getString("player_name"), resultSet.getInt("wins")));
                }
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo cargar wins globales", exception);
            }
            return wins;
        }, executor);
        return track(task);
    }

    public CompletableFuture<Map<UUID, PlayerWins>> loadWinsByChallenge(ChallengeType challengeType) {
        CompletableFuture<Map<UUID, PlayerWins>> task = CompletableFuture.supplyAsync(() -> {
            Map<UUID, PlayerWins> wins = new HashMap<>();
            String sql = "SELECT player_uuid, player_name, wins FROM player_wins_challenge WHERE challenge_type = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, challengeType.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        UUID uuid = UUID.fromString(resultSet.getString("player_uuid"));
                        wins.put(uuid, new PlayerWins(resultSet.getString("player_name"), resultSet.getInt("wins")));
                    }
                }
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo cargar wins por reto", exception);
            }
            return wins;
        }, executor);
        return track(task);
    }

    // ── Connection / lifecycle ───────────────────────────────────────────────

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException exception) {
            return false;
        }
    }

    public int getQueueSize() {
        return executor.getQueue().size();
    }

    public int getActiveTasks() {
        return executor.getActiveCount();
    }

    public int getPendingTasks() {
        return pendingTasks.size();
    }

    public boolean awaitPendingTasks(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        while (System.currentTimeMillis() < deadline) {
            if (pendingTasks.isEmpty() && executor.getQueue().isEmpty() && executor.getActiveCount() == 0) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return pendingTasks.isEmpty();
    }

    public void shutdown() {
        awaitPendingTasks(7000L);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }

        // Deregister any SQLite drivers loaded by this classloader so PlugMan can
        // reload the plugin cleanly without ClassCastException on the next connect().
        try {
            java.util.Enumeration<java.sql.Driver> drivers = DriverManager.getDrivers();
            ClassLoader ownLoader = getClass().getClassLoader();
            while (drivers.hasMoreElements()) {
                java.sql.Driver driver = drivers.nextElement();
                if (driver.getClass().getClassLoader() == ownLoader) {
                    DriverManager.deregisterDriver(driver);
                }
            }
        } catch (SQLException ignored) {
        }
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pendingTasks.add(future);
        future.whenComplete((result, throwable) -> pendingTasks.remove(future));
        return future;
    }

    // ── Records ──────────────────────────────────────────────────────────────

    public record ChallengeStateSnapshot(ChallengeType challengeType, long startTime, long endTime, boolean active) {}

    public record PlayerScore(String playerName, int points) {}

    public record PlayerWins(String playerName, int wins) {}
}
