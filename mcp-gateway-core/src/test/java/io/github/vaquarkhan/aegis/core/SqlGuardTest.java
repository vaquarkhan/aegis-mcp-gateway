/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.governance.SqlReadonlyGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * @author Viquar Khan
 */
class SqlGuardTest {

    private final SqlReadonlyGuard guard = new SqlReadonlyGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "select * from orders",
            "  SELECT count(*) FROM t  ",
            "WITH cte AS (SELECT 1) SELECT * FROM cte",
            "SHOW TABLES",
            "DESCRIBE orders",
            "DESC orders",
            "EXPLAIN SELECT * FROM orders",
            "SELECT * FROM orders;",
            "SELECT\n  a,\n  b\nFROM t"
    })
    void acceptsReadOnlyStatements(String sql) {
        assertTrue(guard.isReadOnly(sql), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO t VALUES (1)",
            "UPDATE t SET a = 1",
            "DELETE FROM t",
            "DROP TABLE t",
            "TRUNCATE TABLE t",
            "CREATE TABLE t (a INT)",
            "ALTER TABLE t ADD COLUMN b INT",
            "MERGE INTO t USING s ON t.a = s.a WHEN MATCHED THEN UPDATE SET t.b = s.b"
    })
    void rejectsWriteStatements(String sql) {
        assertFalse(guard.isReadOnly(sql), sql);
    }

    @Test
    void rejectsStackedStatements() {
        assertFalse(guard.isReadOnly("SELECT 1; DROP TABLE t"));
        assertFalse(guard.isReadOnly("SELECT 1; DROP TABLE t;"));
    }

    @Test
    void rejectsCommentEvasion() {
        assertFalse(guard.isReadOnly("/* SELECT */ DELETE FROM t"));
        assertFalse(guard.isReadOnly("-- SELECT 1\nDROP TABLE t"));
    }

    @Test
    void acceptsLeadingCommentBeforeSelect() {
        assertTrue(guard.isReadOnly("/* daily report */ SELECT * FROM orders"));
        assertTrue(guard.isReadOnly("-- daily report\nSELECT * FROM orders"));
    }

    @Test
    void rejectsNullBlankAndCommentOnly() {
        assertFalse(guard.isReadOnly(null));
        assertFalse(guard.isReadOnly(""));
        assertFalse(guard.isReadOnly("   "));
        assertFalse(guard.isReadOnly("-- nothing here"));
    }

    @Test
    void rejectsPrefixLookalikes() {
        assertFalse(guard.isReadOnly("SELECTED_TABLE_DROP"));
        assertFalse(guard.isReadOnly("WITHDRAW FROM accounts"));
    }
}
