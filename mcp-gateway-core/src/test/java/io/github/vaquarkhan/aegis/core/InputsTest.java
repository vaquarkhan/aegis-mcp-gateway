package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class InputsTest {

    @Test
    void acceptsValidIdentifiers() {
        assertEquals("job-42", Inputs.requireId("job-42"));
        assertEquals("a.b_c-1", Inputs.requireId("a.b_c-1"));
    }

    @Test
    void rejectsTraversalAndSeparators() {
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireId("../etc/passwd"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireId("a/b"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireId("a b"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireId(null));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireId(""));
    }

    @Test
    void validatesIntegers() {
        assertEquals("12", Inputs.requireInt("12"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireInt("-1"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireInt("1.5"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireInt("1234567890"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireInt(null));
    }

    @Test
    void validatesTopics() {
        assertEquals("orders.v1", Inputs.requireTopic("orders.v1"));
        assertEquals("orders_dlq-2", Inputs.requireTopic("orders_dlq-2"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireTopic("."));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireTopic(".."));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireTopic("a/b"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireTopic(null));
    }

    @Test
    void validatesNamespacesAndTables() {
        assertEquals("analytics", Inputs.requireNamespace("analytics"));
        assertEquals("analytics.gold", Inputs.requireNamespace("analytics.gold"));
        assertEquals("orders", Inputs.requireTable("orders"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireNamespace("bad..ns"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireTable("drop table"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireTable(""));
    }

    @Test
    void boundsSqlLength() {
        assertEquals("SELECT 1", Inputs.requireSql("SELECT 1", 100));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireSql("SELECT 1", 4));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireSql("   ", 100));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireSql(null, 100));
    }

    @Test
    void jarUploadFailsClosedWithoutAllowList() {
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireJarPath("/tmp/app.jar", Set.of()));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireJarPath("/tmp/app.jar", null));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requireJarPath(null, Set.of("/tmp")));
    }

    @Test
    void escapesJsonControlCharacters() {
        assertEquals("a\\\"b", Inputs.jsonEscape("a\"b"));
        assertEquals("a\\\\b", Inputs.jsonEscape("a\\b"));
        assertEquals("a\\nb", Inputs.jsonEscape("a\nb"));
        assertEquals("a\\tb", Inputs.jsonEscape("a\tb"));
        assertEquals("\\u0001", Inputs.jsonEscape("\u0001"));
        assertEquals("", Inputs.jsonEscape(null));
    }

    @Test
    void validatesPathsAndRejectsTraversal() {
        assertEquals("v1/status", Inputs.requirePath("v1/status"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requirePath("a/../b"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requirePath("../etc/passwd"));
        assertThrows(Inputs.InvalidInput.class, () -> Inputs.requirePath(null));
    }
}
