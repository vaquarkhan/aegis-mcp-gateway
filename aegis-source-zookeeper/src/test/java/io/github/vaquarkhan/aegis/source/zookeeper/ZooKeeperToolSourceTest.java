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
package io.github.vaquarkhan.aegis.source.zookeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ZooKeeper source against a <b>real ZooKeeper server</b>, started in-process by
 * {@code curator-test}. No test double: real client protocol, real session, real {@code Stat} versions,
 * real {@link org.apache.zookeeper.KeeperException} codes, real path validation.
 */
class ZooKeeperToolSourceTest {

    static TestingServer server;
    static ZooKeeper admin;

    @BeforeAll
    static void startRealZooKeeper() throws Exception {
        server = new TestingServer(true);
        admin = ZooKeeperToolSourceFactory.connect(server.getConnectString(), 15_000, 20_000, "digest", "aegis:secret");
    }

    @AfterAll
    static void stopRealZooKeeper() throws Exception {
        if (admin != null) {
            admin.close();
        }
        if (server != null) {
            server.close();
        }
    }

    ZooKeeperToolSource source;

    @BeforeEach
    void seedRealTree() throws Exception {
        create("/aegis", "root");
        create("/aegis/orders", "order-config");
        create("/aegis/orders/shard-1", "s1");
        create("/aegis/orders/shard-2", "s2");
        source = new ZooKeeperToolSource("zk", 32, 2,
                () -> ZooKeeperToolSourceFactory.connect(server.getConnectString(), 15_000, 20_000, "digest", "aegis:secret"));
    }

    @AfterEach
    void clearRealTree() throws Exception {
        source.close();
        deleteRecursively("/aegis");
    }

    static void create(String path, String data) throws Exception {
        if (admin.exists(path, false) == null) {
            admin.create(path, data.getBytes(StandardCharsets.UTF_8),
                    ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
        }
    }

    static void deleteRecursively(String path) throws Exception {
        if (admin.exists(path, false) == null) {
            return;
        }
        for (String child : admin.getChildren(path, false)) {
            deleteRecursively(path + "/" + child);
        }
        admin.delete(path, -1);
    }

    ToolResult call(String tool, Map<String, Object> args) throws Exception {
        for (ToolSource.SourcedTool t : source.discover()) {
            if (t.descriptor().name().equals(tool)) {
                return t.executor().execute(new ExecutionContext("req-1", tool, args,
                        CallerIdentity.of("ana", Set.of("analysts"), "idp", "jwt"), Map.of(), 30_000, false));
            }
        }
        throw new AssertionError("no such tool: " + tool);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ToolResult r) {
        Object structured = r.body().get("structuredContent");
        return structured instanceof Map ? (Map<String, Object>) structured : Map.of();
    }

    static String text(ToolResult r) {
        Object content = r.body().get("content");
        if (content instanceof List<?> parts && !parts.isEmpty() && parts.get(0) instanceof Map<?, ?> first) {
            return String.valueOf(first.get("text"));
        }
        return "";
    }

    // ------------------------------------------------------------------ availability

    @Test
    void anEnsembleThatCannotBeReachedIsABackendAvailabilityFailure() throws Exception {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        String unreachable = "127.0.0.1:" + closedPort;
        ZooKeeperToolSource down = new ZooKeeperToolSource("zk-down", 32, 2,
                () -> ZooKeeperToolSourceFactory.connect(unreachable, 15_000, 300, "digest", "aegis:secret"));
        try {
            ToolSource.SourcedTool list = down.discover().stream()
                    .filter(t -> t.descriptor().name().equals("zookeeper_list_children")).findFirst().orElseThrow();
            io.github.vaquarkhan.aegis.engine.spi.ToolExecutor.ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(
                    io.github.vaquarkhan.aegis.engine.spi.ToolExecutor.ExecutionException.class,
                    () -> list.executor().execute(new ExecutionContext("req-2", "zookeeper_list_children",
                            Map.of("path", "/aegis"), CallerIdentity.of("ana", Set.of("analysts"), "idp", "jwt"),
                            Map.of(), 30_000, false)));
            assertTrue(e.getMessage().startsWith("ZooKeeper is unavailable: the ensemble did not connect"), e.getMessage());
            assertTrue(e.availabilityFailure(), "the circuit breaker must count an unreachable ensemble");
            assertFalse(e.getMessage().contains(unreachable), "the connect string is configuration, not caller output");
        } finally {
            down.close();
        }
    }

    // ------------------------------------------------------------------ surface

    @Test
    void theToolSurfaceIsTheDataTree() {
        List<String> names = source.discover().stream().map(t -> t.descriptor().name()).toList();
        assertEquals(List.of("zookeeper_list_children", "zookeeper_stat_node", "zookeeper_get_node",
                "zookeeper_create_node", "zookeeper_set_data", "zookeeper_delete_node"), names);
    }

    // ------------------------------------------------------------------ reads against the real server

    @Test
    void listChildrenReturnsTheRealChildrenSortedAndCapped() throws Exception {
        create("/aegis/orders/shard-3", "s3");
        ToolResult r = call("zookeeper_list_children", Map.of("path", "/aegis/orders"));
        assertFalse(r.isError(), text(r));
        Map<String, Object> b = body(r);
        // maxChildren is 2 in this test and the real node has three children.
        assertEquals(List.of("shard-1", "shard-2"), b.get("children"));
        assertEquals(Boolean.TRUE, b.get("truncated"));
        assertNotNull(b.get("note"));
    }

    @Test
    void statNodeReturnsTheRealVersionAndNoData() throws Exception {
        ToolResult r = call("zookeeper_stat_node", Map.of("path", "/aegis/orders"));
        assertFalse(r.isError(), text(r));
        Map<String, Object> b = body(r);
        assertEquals(0L, ((Number) b.get("version")).longValue(), "a freshly created node is version 0");
        assertEquals(2L, ((Number) b.get("numChildren")).longValue());
        assertNull(b.get("data"), "stat must not disclose node data");
    }

    @Test
    void getNodeReturnsRealDataAsText() throws Exception {
        ToolResult r = call("zookeeper_get_node", Map.of("path", "/aegis/orders"));
        assertFalse(r.isError(), text(r));
        Map<String, Object> b = body(r);
        assertEquals("order-config", b.get("data"));
        assertEquals("utf-8", b.get("encoding"));
        assertEquals(Boolean.FALSE, b.get("dataTruncated"));
    }

    @Test
    void binaryNodeDataComesBackBase64RatherThanMojibake() throws Exception {
        byte[] notUtf8 = {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xFF};
        admin.setData("/aegis/orders", notUtf8, -1);
        ToolResult r = call("zookeeper_get_node", Map.of("path", "/aegis/orders"));
        assertFalse(r.isError(), text(r));
        Map<String, Object> b = body(r);
        assertEquals("base64", b.get("encoding"),
                "invalid UTF-8 must not be handed to a model as text");
        assertEquals(java.util.Base64.getEncoder().encodeToString(notUtf8), b.get("data"));
    }

    @Test
    void largeNodeDataIsTruncatedAndSaysSo() throws Exception {
        admin.setData("/aegis/orders", "y".repeat(500).getBytes(StandardCharsets.UTF_8), -1);
        ToolResult r = call("zookeeper_get_node", Map.of("path", "/aegis/orders"));
        Map<String, Object> b = body(r);
        assertEquals(Boolean.TRUE, b.get("dataTruncated"));
        assertEquals(32, String.valueOf(b.get("data")).length(), "maxDataBytes is 32 in this test");
        assertEquals(500L, ((Number) b.get("dataBytes")).longValue(), "the real length is still reported");
    }

    // ------------------------------------------------------------------ writes against the real server

    @Test
    void createNodeReallyCreatesAnOwnerOnlyNode() throws Exception {
        ToolResult r = call("zookeeper_create_node", Map.of("path", "/aegis/new-node", "data", "hello"));
        assertFalse(r.isError(), text(r));
        try (ZooKeeper owner = ZooKeeperToolSourceFactory.connect(server.getConnectString(), 15_000, 20_000, "digest", "aegis:secret")) {
            Stat stat = owner.exists("/aegis/new-node", false);
            assertNotNull(stat, "the node exists on the real server");
            assertEquals("hello", new String(owner.getData("/aegis/new-node", false, new Stat()), StandardCharsets.UTF_8));
            var acls = owner.getACL("/aegis/new-node", new Stat());
            assertEquals(1, acls.size(), "a created znode has one creator ACL");
            assertEquals(ZooDefs.Perms.ALL, acls.get(0).getPerms());
            assertEquals("digest", acls.get(0).getId().getScheme());
            assertFalse("world".equals(acls.get(0).getId().getScheme()),
                    "Aegis must never create a world-writable znode");
        }
    }

    @Test
    void createNodeOnAnExistingPathIsACallerError() throws Exception {
        ToolResult r = call("zookeeper_create_node", Map.of("path", "/aegis/orders", "data", "x"));
        assertTrue(r.isError());
        assertTrue(text(r).contains("NODEEXISTS"), text(r));
    }

    @Test
    void setDataWithTheWrongVersionIsRefusedByTheRealServer() throws Exception {
        ToolResult r = call("zookeeper_set_data", Map.of("path", "/aegis/orders", "version", 7, "data", "new"));
        assertTrue(r.isError(), "version 7 does not match the real node");
        assertTrue(text(r).contains("BADVERSION"), text(r));
        assertEquals("order-config",
                new String(admin.getData("/aegis/orders", false, new Stat()), StandardCharsets.UTF_8),
                "the real data is unchanged");
    }

    @Test
    void setDataWithTheRightVersionReallyWritesAndBumpsTheVersion() throws Exception {
        ToolResult r = call("zookeeper_set_data", Map.of("path", "/aegis/orders", "version", 0, "data", "updated"));
        assertFalse(r.isError(), text(r));
        assertEquals(1L, ((Number) body(r).get("version")).longValue(), "ZooKeeper bumped the version");
        assertEquals("updated",
                new String(admin.getData("/aegis/orders", false, new Stat()), StandardCharsets.UTF_8));
    }

    @Test
    void deleteNodeWithChildrenIsRefusedByTheRealServer() throws Exception {
        ToolResult r = call("zookeeper_delete_node", Map.of("path", "/aegis/orders", "version", 0));
        assertTrue(r.isError());
        assertTrue(text(r).contains("NOTEMPTY"), text(r));
        assertNotNull(admin.exists("/aegis/orders", false), "the real node survives");
    }

    @Test
    void deleteNodeReallyDeletesALeaf() throws Exception {
        ToolResult r = call("zookeeper_delete_node", Map.of("path", "/aegis/orders/shard-1", "version", 0));
        assertFalse(r.isError(), text(r));
        assertNull(admin.exists("/aegis/orders/shard-1", false), "the real node is gone");
    }

    @Test
    void deletingSomethingThatIsNotThereIsACallerError() throws Exception {
        ToolResult r = call("zookeeper_delete_node", Map.of("path", "/aegis/nope", "version", -1));
        assertTrue(r.isError());
        assertTrue(text(r).contains("NONODE"), text(r));
    }

    // ------------------------------------------------------------------ path validation, by ZooKeeper itself

    @Test
    void zooKeepersOwnPathRulesRejectBadPaths() throws Exception {
        for (String bad : List.of("relative/path", "/aegis/../etc", "/aegis//double", "/aegis/trailing/")) {
            ToolResult r = call("zookeeper_list_children", Map.of("path", bad));
            assertTrue(r.isError(), bad + " should have been refused");
            assertTrue(text(r).startsWith("Invalid path:"), text(r));
        }
    }

    @Test
    void aValidDeepPathIsAccepted() throws Exception {
        ToolResult r = call("zookeeper_stat_node", Map.of("path", "/aegis/orders/shard-2"));
        assertFalse(r.isError(), text(r));
        assertEquals("/aegis/orders/shard-2", body(r).get("path"));
    }
}
