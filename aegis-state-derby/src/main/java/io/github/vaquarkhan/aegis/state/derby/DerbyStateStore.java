/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.state.derby;

import io.github.vaquarkhan.aegis.engine.spi.HighWaterStateStore;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Durable local {@link StateStore} using embedded Apache Derby.
 *
 * <p>This is intentionally a <b>single-process</b> provider. Derby's embedded database lock prevents
 * another JVM from opening the same writable database. A failure to acquire that lock prevents Aegis
 * startup rather than creating two independent approval/receipt stores. For active-active deployment,
 * use a future shared provider such as ZooKeeper, not a file-backed embedded database.
 *
 * <p>All public operations synchronize one JDBC connection and use a database transaction. That makes
 * {@code putIfAbsent}, {@code take}, and {@code tryAcquire} atomic with respect to every Aegis request
 * in the owning process. The database lock makes that process the only writer.
 */
public final class DerbyStateStore implements HighWaterStateStore, AutoCloseable {

    private static final String STATE_TABLE = "AEGIS_STATE";
    private static final String WINDOW_TABLE = "AEGIS_WINDOW";
    private final Connection connection;
    private final String databaseUrl;
    private final Clock clock;

    public DerbyStateStore(Path path, Clock clock) throws SQLException {
        this.clock = clock;
        Path database = path.toAbsolutePath();
        try {
            Files.createDirectories(database.getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("cannot create Derby state parent directory", e);
        }
        // Derby's error-log setting is JVM-wide. Set it for every open because tests and embedding hosts
        // can create stores with different temporary directories during one JVM lifetime. Keeping the
        // log beside, rather than inside, the database directory avoids a missing-directory race before
        // Derby creates the database.
        Path log = database.resolveSibling(database.getFileName() + ".derby.log");
        if (System.getProperty("derby.stream.error.file") == null) {
            System.setProperty("derby.stream.error.file", log.toString());
        }
        try {
            Class.forName("org.apache.derby.iapi.jdbc.AutoloadedDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Apache Derby driver is not on the classpath", e);
        }
        databaseUrl = "jdbc:derby:" + database;
        connection = DriverManager.getConnection(databaseUrl + ";create=true");
        connection.setAutoCommit(false);
        createTables();
    }

    private void createTables() throws SQLException {
        try (Statement s = connection.createStatement()) {
            createIgnoringAlreadyExists(s, "CREATE TABLE " + STATE_TABLE
                    + " (STATE_KEY VARCHAR(2048) NOT NULL PRIMARY KEY, STATE_VALUE VARCHAR(32672) NOT NULL, EXPIRES_AT BIGINT)");
            createIgnoringAlreadyExists(s, "CREATE TABLE " + WINDOW_TABLE
                    + " (WINDOW_KEY VARCHAR(2048) NOT NULL PRIMARY KEY, WINDOW_START BIGINT NOT NULL, USED_VALUE BIGINT NOT NULL)");
            connection.commit();
        } catch (SQLException e) {
            rollback();
            throw e;
        }
    }

    private static void createIgnoringAlreadyExists(Statement statement, String sql) throws SQLException {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            // Derby SQLState X0Y32 means the table is already present, which is the normal restart path.
            if (!"X0Y32".equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    @Override
    public synchronized boolean putIfAbsent(String key, String value, Instant expiresAt) {
        return transaction(() -> {
            Entry current = entryForUpdate(key);
            long now = clock.instant().toEpochMilli();
            if (current != null && current.live(now)) {
                return false;
            }
            if (current != null) {
                deleteState(key);
            }
            insertState(key, value, expiresAt);
            return true;
        });
    }

    @Override
    public synchronized void put(String key, String value, Instant expiresAt) {
        transaction(() -> {
            Entry current = entryForUpdate(key);
            if (current == null) {
                insertState(key, value, expiresAt);
            } else {
                try (PreparedStatement s = connection.prepareStatement("UPDATE " + STATE_TABLE
                        + " SET STATE_VALUE=?, EXPIRES_AT=? WHERE STATE_KEY=?")) {
                    s.setString(1, value);
                    expires(s, 2, expiresAt);
                    s.setString(3, key);
                    s.executeUpdate();
                }
            }
            return null;
        });
    }

    @Override
    public synchronized Optional<String> get(String key) {
        return transaction(() -> {
            Entry current = entryForUpdate(key);
            if (current == null) {
                return Optional.empty();
            }
            if (!current.live(clock.instant().toEpochMilli())) {
                deleteState(key);
                return Optional.empty();
            }
            return Optional.of(current.value());
        });
    }

    @Override
    public synchronized Optional<String> take(String key) {
        return transaction(() -> {
            Entry current = entryForUpdate(key);
            if (current == null) {
                return Optional.empty();
            }
            deleteState(key);
            return current.live(clock.instant().toEpochMilli()) ? Optional.of(current.value()) : Optional.empty();
        });
    }

    @Override
    public synchronized Map<String, String> scan(String prefix, int limit) {
        if (limit <= 0) {
            return Map.of();
        }
        return transaction(() -> {
            Map<String, String> out = new LinkedHashMap<>();
            long now = clock.instant().toEpochMilli();
            try (PreparedStatement s = connection.prepareStatement("SELECT STATE_KEY, STATE_VALUE, EXPIRES_AT FROM "
                    + STATE_TABLE + " WHERE STATE_KEY LIKE ? ESCAPE '\\' ORDER BY STATE_KEY")) {
                s.setString(1, likePrefix(prefix));
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString(1);
                        Long expiry = nullableLong(rs, 3);
                        if (expiry == null || now < expiry) {
                            out.put(key, rs.getString(2));
                            if (out.size() >= limit) {
                                break;
                            }
                        } else {
                            deleteState(key);
                        }
                    }
                }
            }
            return out;
        });
    }

    @Override
    public synchronized long updateHighWaterMark(String key, long candidate) {
        if (candidate < 0) {
            throw new IllegalArgumentException("high-water candidate must not be negative");
        }
        return transaction(() -> {
            Entry current = entryForUpdate(key);
            long existing = current == null ? 0 : highWater(current.value());
            long next = Math.max(existing, candidate);
            if (next > existing) {
                if (current == null) {
                    insertState(key, Long.toString(next), null);
                } else {
                    try (PreparedStatement s = connection.prepareStatement("UPDATE " + STATE_TABLE
                            + " SET STATE_VALUE=?, EXPIRES_AT=NULL WHERE STATE_KEY=?")) {
                        s.setString(1, Long.toString(next));
                        s.setString(2, key);
                        s.executeUpdate();
                    }
                }
            }
            return next;
        });
    }

    private static long highWater(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("state store holds an unreadable high-water mark", e);
        }
    }

    @Override
    public synchronized boolean tryAcquire(String key, long cost, long capacity, Duration window, Instant now) {
        if (cost < 0 || capacity < 0 || window.isZero() || window.isNegative()) {
            return false;
        }
        return transaction(() -> {
            long width = window.toMillis();
            long nowMillis = now.toEpochMilli();
            long start = nowMillis - Math.floorMod(nowMillis, width);
            Window current = windowForUpdate(key);
            long used = current == null || current.start() != start ? 0 : current.used();
            if (cost > capacity - used) {
                return false; // Crucial: a refused call does not alter the counter.
            }
            if (current == null) {
                try (PreparedStatement s = connection.prepareStatement("INSERT INTO " + WINDOW_TABLE
                        + " (WINDOW_KEY, WINDOW_START, USED_VALUE) VALUES (?, ?, ?)")) {
                    s.setString(1, key);
                    s.setLong(2, start);
                    s.setLong(3, cost);
                    s.executeUpdate();
                }
            } else {
                try (PreparedStatement s = connection.prepareStatement("UPDATE " + WINDOW_TABLE
                        + " SET WINDOW_START=?, USED_VALUE=? WHERE WINDOW_KEY=?")) {
                    s.setLong(1, start);
                    s.setLong(2, used + cost);
                    s.setString(3, key);
                    s.executeUpdate();
                }
            }
            return true;
        });
    }

    private Entry entryForUpdate(String key) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("SELECT STATE_VALUE, EXPIRES_AT FROM " + STATE_TABLE
                + " WHERE STATE_KEY=? FOR UPDATE")) {
            s.setString(1, key);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Entry(rs.getString(1), nullableLong(rs, 2)) : null;
            }
        }
    }

    private Window windowForUpdate(String key) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("SELECT WINDOW_START, USED_VALUE FROM " + WINDOW_TABLE
                + " WHERE WINDOW_KEY=? FOR UPDATE")) {
            s.setString(1, key);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Window(rs.getLong(1), rs.getLong(2)) : null;
            }
        }
    }

    private void insertState(String key, String value, Instant expiresAt) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO " + STATE_TABLE
                + " (STATE_KEY, STATE_VALUE, EXPIRES_AT) VALUES (?, ?, ?)")) {
            s.setString(1, key);
            s.setString(2, value);
            expires(s, 3, expiresAt);
            s.executeUpdate();
        }
    }

    private void deleteState(String key) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM " + STATE_TABLE + " WHERE STATE_KEY=?")) {
            s.setString(1, key);
            s.executeUpdate();
        }
    }

    private static void expires(PreparedStatement statement, int index, Instant expiresAt) throws SQLException {
        if (expiresAt == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, expiresAt.toEpochMilli());
        }
    }

    private static Long nullableLong(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }

    private static String likePrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private <T> T transaction(SqlWork<T> work) {
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("Derby state operation failed: " + e.getSQLState(), e);
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original database failure is more useful to the caller.
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
        try {
            DriverManager.getConnection(databaseUrl + ";shutdown=true");
            throw new SQLException("Derby shutdown unexpectedly returned normally");
        } catch (SQLException expected) {
            // Derby signals a successful embedded shutdown with SQLState 08006. Any other value means
            // the lock may still be held, which must surface rather than leaving a second Aegis process
            // believing it has exclusive state.
            if (!"08006".equals(expected.getSQLState())) {
                throw expected;
            }
        }
    }

    private record Entry(String value, Long expiresAt) {
        boolean live(long now) {
            return expiresAt == null || now < expiresAt;
        }
    }

    private record Window(long start, long used) {}

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }
}
