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
        File dbFile = new File(plugin.getDataFolder(), "competition.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA synchronous=NORMAL;");
            statement.execute("CREATE TABLE IF NOT EXISTS challenge_state ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1),"
                    + "challenge_type TEXT,"
                    + "start_time INTEGER,"
                    + "end_time INTEGER,"
                    + "active INTEGER NOT NULL DEFAULT 0"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS player_progress ("
                    + "challenge_type TEXT NOT NULL,"
                    + "player_uuid TEXT NOT NULL,"
                    + "player_name TEXT NOT NULL,"
                    + "points INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (challenge_type, player_uuid)"
                    + ")");

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

    public CompletableFuture<Void> saveChallengeState(ChallengeType challengeType, long startTime, long endTime, boolean active) {
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO challenge_state (id, challenge_type, start_time, end_time, active) VALUES (1, ?, ?, ?, ?) "
                    + "ON CONFLICT(id) DO UPDATE SET challenge_type = excluded.challenge_type, start_time = excluded.start_time, "
                    + "end_time = excluded.end_time, active = excluded.active";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, challengeType == null ? null : challengeType.name());
                statement.setLong(2, startTime);
                statement.setLong(3, endTime);
                statement.setInt(4, active ? 1 : 0);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo guardar challenge_state", exception);
            }
        }, executor);
        return track(task);
    }

    public CompletableFuture<ChallengeStateSnapshot> loadChallengeState() {
        CompletableFuture<ChallengeStateSnapshot> task = CompletableFuture.supplyAsync(() -> {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT challenge_type, start_time, end_time, active FROM challenge_state WHERE id = 1")) {
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
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo cargar challenge_state", exception);
            }
        }, executor);
        return track(task);
    }

    public CompletableFuture<Void> clearProgress(ChallengeType challengeType) {
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM player_progress WHERE challenge_type = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, challengeType.name());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo limpiar progreso", exception);
            }
        }, executor);
        return track(task);
    }

    public CompletableFuture<Map<UUID, PlayerScore>> loadProgress(ChallengeType challengeType) {
        CompletableFuture<Map<UUID, PlayerScore>> task = CompletableFuture.supplyAsync(() -> {
            Map<UUID, PlayerScore> data = new HashMap<>();
            String sql = "SELECT player_uuid, player_name, points FROM player_progress WHERE challenge_type = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, challengeType.name());
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

    public CompletableFuture<Void> upsertPlayerScore(ChallengeType challengeType, UUID playerUuid, String playerName, int points) {
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_progress (challenge_type, player_uuid, player_name, points) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT(challenge_type, player_uuid) DO UPDATE SET player_name = excluded.player_name, points = excluded.points";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, challengeType.name());
                statement.setString(2, playerUuid.toString());
                statement.setString(3, playerName);
                statement.setInt(4, points);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo guardar score", exception);
            }
        }, executor);
        return track(task);
    }

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

    public CompletableFuture<Void> addPlacedBlock(BlockKey blockKey) {
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR IGNORE INTO placed_blocks(world, x, y, z) VALUES (?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, blockKey.world());
                statement.setInt(2, blockKey.x());
                statement.setInt(3, blockKey.y());
                statement.setInt(4, blockKey.z());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo agregar placed_block", exception);
            }
        }, executor);
        return track(task);
    }

    public CompletableFuture<Void> removePlacedBlock(BlockKey blockKey) {
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, blockKey.world());
                statement.setInt(2, blockKey.x());
                statement.setInt(3, blockKey.y());
                statement.setInt(4, blockKey.z());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new RuntimeException("No se pudo eliminar placed_block", exception);
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

    public CompletableFuture<Void> recordWinner(ChallengeType challengeType, UUID playerUuid, String playerName, int points, long endedAt) {
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
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                throw new RuntimeException("No se pudo registrar ganador", exception);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
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
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pendingTasks.add(future);
        future.whenComplete((result, throwable) -> pendingTasks.remove(future));
        return future;
    }

    public record ChallengeStateSnapshot(ChallengeType challengeType, long startTime, long endTime, boolean active) {
    }

    public record PlayerScore(String playerName, int points) {
    }

    public record PlayerWins(String playerName, int wins) {
    }
}
