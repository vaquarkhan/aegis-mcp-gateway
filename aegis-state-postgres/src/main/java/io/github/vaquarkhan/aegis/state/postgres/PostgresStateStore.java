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
package io.github.vaquarkhan.aegis.state.postgres;

import io.github.vaquarkhan.aegis.engine.spi.StateStore;
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
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared {@link StateStore} using PostgreSQL transactions.
 *
 * <p>Every SPI call opens one short PostgreSQL transaction. Atomic SQL statements, PostgreSQL unique
 * constraints and row-level locks provide cross-process semantics: only one gateway can consume an
 * approval/receipt nonce, and a refused rate acquisition never changes the counter. There is intentionally
 * no JVM-wide synchronization or local cache: sharing is the purpose of this provider.
 *
 * <p>The database role needs only {@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE} on the
 * two Aegis tables after the schema is created. Production deployments should provision a role/database with
 * TLS and least privilege; startup DDL is idempotent but still requires table-creation privilege on first use.
 */
public final class PostgresStateStore implements StateStore, AutoCloseable {

    static final String STATE_TABLE = "aegis_state";
    static final String WINDOW_TABLE = "aegis_window";

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String namespace;
    private final int connectTimeoutSeconds;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PostgresStateStore(String jdbcUrl, String username, String password, String namespace,
            int connectTimeoutSeconds, Clock clock) throws SQLException {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.namespace = namespace;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.clock = clock;
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver is not on the classpath", e);
        }
        createTables();
    }

    private void createTables() throws SQLException {
        transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + STATE_TABLE + " ("
                        + "namespace VARCHAR(128) NOT NULL, state_key VARCHAR(2048) NOT NULL, "
                        + "state_value TEXT NOT NULL, expires_at_ms BIGINT, "
                        + "PRIMARY KEY(namespace, state_key))");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + WINDOW_TABLE + " ("
                        + "namespace VARCHAR(128) NOT NULL, window_key VARCHAR(2048) NOT NULL, "
                        + "window_start_ms BIGINT NOT NULL, used_value BIGINT NOT NULL, "
                        + "PRIMARY KEY(namespace, window_key))");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS aegis_state_expiry_idx ON " + STATE_TABLE
                        + " (namespace, expires_at_ms)");
            }
            return null;
        });
    }

    @Override
    public boolean putIfAbsent(String key, String value, Instant expiresAt) {
        long now = clock.instant().toEpochMilli();
        Long expiry = expiresAt == null ? null : expiresAt.toEpochMilli();
        return transaction(connection -> {
            String sql = "INSERT INTO " + STATE_TABLE + " AS existing "
                    + "(namespace, state_key, state_value, expires_at_ms) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (namespace, state_key) DO UPDATE SET state_value = EXCLUDED.state_value, "
                    + "expires_at_ms = EXCLUDED.expires_at_ms "
                    + "WHERE existing.expires_at_ms IS NOT NULL AND existing.expires_at_ms <= ? RETURNING state_key";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                statement.setString(3, value);
                nullableLong(statement, 4, expiry);
                statement.setLong(5, now);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        });
    }

    @Override
    public void put(String key, String value, Instant expiresAt) {
        Long expiry = expiresAt == null ? null : expiresAt.toEpochMilli();
        transaction(connection -> {
            String sql = "INSERT INTO " + STATE_TABLE + " (namespace, state_key, state_value, expires_at_ms) "
                    + "VALUES (?, ?, ?, ?) ON CONFLICT (namespace, state_key) DO UPDATE SET "
                    + "state_value = EXCLUDED.state_value, expires_at_ms = EXCLUDED.expires_at_ms";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                statement.setString(3, value);
                nullableLong(statement, 4, expiry);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<String> get(String key) {
        long now = clock.instant().toEpochMilli();
        return transaction(connection -> {
            Entry entry = selectForUpdate(connection, key);
            if (entry == null) {
                return Optional.empty();
            }
            if (!entry.live(now)) {
                delete(connection, key);
                return Optional.empty();
            }
            return Optional.of(entry.value());
        });
    }

    @Override
    public Optional<String> take(String key) {
        long now = clock.instant().toEpochMilli();
        return transaction(connection -> {
            String sql = "DELETE FROM " + STATE_TABLE + " WHERE namespace = ? AND state_key = ? "
                    + "RETURNING state_value, expires_at_ms";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    Entry entry = new Entry(result.getString(1), nullableLong(result, 2));
                    return entry.live(now) ? Optional.of(entry.value()) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Map<String, String> scan(String prefix, int limit) {
        if (limit <= 0) {
            return Map.of();
        }
        long now = clock.instant().toEpochMilli();
        return transaction(connection -> {
            try (PreparedStatement purge = connection.prepareStatement("DELETE FROM " + STATE_TABLE
                    + " WHERE namespace = ? AND expires_at_ms IS NOT NULL AND expires_at_ms <= ?")) {
                purge.setString(1, namespace);
                purge.setLong(2, now);
                purge.executeUpdate();
            }
            Map<String, String> out = new LinkedHashMap<>();
            String sql = "SELECT state_key, state_value FROM " + STATE_TABLE + " WHERE namespace = ? "
                    + "AND state_key LIKE ? ESCAPE '\\' ORDER BY state_key LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, namespace);
                statement.setString(2, likePrefix(prefix));
                statement.setInt(3, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        out.put(result.getString(1), result.getString(2));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public boolean tryAcquire(String key, long cost, long capacity, Duration window, Instant now) {
        if (cost < 0 || capacity < 0 || window.isZero() || window.isNegative() || cost > capacity) {
            return false;
        }
        long width;
        try {
            width = window.toMillis();
        } catch (ArithmeticException e) {
            return false;
        }
        if (width <= 0) {
            return false;
        }
        long start = Math.floorDiv(now.toEpochMilli(), width) * width;
        return transaction(connection -> {
            String sql = "INSERT INTO " + WINDOW_TABLE + " AS existing "
                    + "(namespace, window_key, window_start_ms, used_value) "
                    + "SELECT ?, ?, ?, ? WHERE ? <= ? "
                    + "ON CONFLICT (namespace, window_key) DO UPDATE SET "
                    + "window_start_ms = EXCLUDED.window_start_ms, "
                    + "used_value = CASE WHEN existing.window_start_ms = EXCLUDED.window_start_ms "
                    + "THEN existing.used_value + EXCLUDED.used_value ELSE EXCLUDED.used_value END "
                    + "WHERE CASE WHEN existing.window_start_ms = EXCLUDED.window_start_ms "
                    + "THEN existing.used_value <= ? - EXCLUDED.used_value ELSE EXCLUDED.used_value <= ? END "
                    + "RETURNING used_value";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                statement.setLong(3, start);
                statement.setLong(4, cost);
                statement.setLong(5, cost);
                statement.setLong(6, capacity);
                statement.setLong(7, capacity);
                statement.setLong(8, capacity);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        });
    }

    private Entry selectForUpdate(Connection connection, String key) throws SQLException {
        String sql = "SELECT state_value, expires_at_ms FROM " + STATE_TABLE
                + " WHERE namespace = ? AND state_key = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Entry(result.getString(1), nullableLong(result, 2)) : null;
            }
        }
    }

    private void delete(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + STATE_TABLE
                + " WHERE namespace = ? AND state_key = ?")) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }

    private Connection open() throws SQLException {
        if (closed.get()) {
            throw new IllegalStateException("PostgreSQL StateStore is closed");
        }
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", Integer.toString(connectTimeoutSeconds));
        properties.setProperty("ApplicationName", "aegis-state-postgres");
        Connection connection = DriverManager.getConnection(jdbcUrl, properties);
        connection.setAutoCommit(false);
        return connection;
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = open()) {
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollback(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL governance state operation failed", e);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original database failure determines the fail-closed result.
        }
    }

    private static void nullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet result, int index) throws SQLException {
        long value = result.getLong(index);
        return result.wasNull() ? null : value;
    }

    private static String likePrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private record Entry(String value, Long expiresAtMs) {
        boolean live(long now) {
            return expiresAtMs == null || now < expiresAtMs;
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
