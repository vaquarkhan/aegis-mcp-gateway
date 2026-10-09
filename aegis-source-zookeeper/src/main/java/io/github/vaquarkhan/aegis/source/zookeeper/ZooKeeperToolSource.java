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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.Stat;

/**
 * Apache ZooKeeper's data tree as governed MCP tools, through ZooKeeper's own Java client.
 *
 * <p>ZooKeeper's AdminServer serves cluster commands such as {@code /commands/ruok} over HTTP, and those
 * belong in a declarative HTTP source. The data tree does not: it is reached over ZooKeeper's client
 * protocol. So this source speaks that protocol, and every operation below is a typed method on the
 * ZooKeeper client with paths validated by ZooKeeper's own {@link PathUtils}.
 *
 * <p>Three design points worth knowing:
 *
 * <ul>
 *   <li><b>A write carries the version it expects.</b> {@code zookeeper_set_data} and
 *       {@code zookeeper_delete_node} both require {@code version}, which ZooKeeper checks
 *       optimistically. An agent therefore has to read a node before it can change or remove it, and a
 *       node that moved underneath it gets {@code BADVERSION} instead of a silent overwrite. Passing
 *       {@code -1} disables the check; the schema allows it so an operator can, and the tool description
 *       says what it costs.</li>
 *   <li><b>Node data is bytes, not text.</b> A znode can hold arbitrary binary. Data that decodes as
 *       UTF-8 is returned as text; anything else is returned base64-encoded with {@code encoding} set,
 *       so a model is never handed mojibake and asked to reason about it.</li>
 *   <li><b>Created nodes are owner-only.</b> The source requires authenticated ZooKeeper digest
 *       credentials and creates persistent znodes with {@code CREATOR_ALL_ACL}; it never creates a
 *       world-writable node merely because an MCP caller was approved.</li>
 *   <li><b>Egress control does not apply.</b> The client opens its own socket, so
 *       {@link ToolExecutor#egressTargets} reports nothing. The connect string is configuration rather
 *       than an argument, so an agent cannot redirect it.</li>
 * </ul>
 *
 * <p>Capability only. Tool classes, resources and dry-run modes come from the operator's {@code tools:}
 * section, and the reserved {@code /zookeeper} subtree is protected by a policy rule in the
 * configuration rather than by a check compiled in here.
 */
public final class ZooKeeperToolSource implements ToolSource {

    private static final String PATH_PROP =
            "\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1024}";
    private static final String VERSION_PROP =
            "\"version\":{\"type\":\"integer\",\"minimum\":-1,\"maximum\":2147483647}";

    private final String id;
    private final int maxDataBytes;
    private final int maxChildren;
    private final Supplier<ZooKeeper> clientSupplier;
    private ZooKeeper client;

    public ZooKeeperToolSource(String id, int maxDataBytes, int maxChildren, Supplier<ZooKeeper> clientSupplier) {
        this.id = id;
        this.maxDataBytes = maxDataBytes;
        this.maxChildren = maxChildren;
        this.clientSupplier = clientSupplier;
    }

    @Override
    public String id() {
        return id;
    }

    private synchronized ZooKeeper client() {
        if (client == null || !client.getState().isAlive()) {
            client = clientSupplier.get();
        }
        return client;
    }

    @Override
    public List<SourcedTool> discover() {
        return List.of(
                tool("zookeeper_list_children", "Child node names directly under one znode.",
                        "{\"type\":\"object\",\"properties\":{" + PATH_PROP + "},"
                                + "\"required\":[\"path\"],\"additionalProperties\":false}", this::listChildren),
                tool("zookeeper_stat_node", "Metadata for one znode: version, data length, child count. "
                                + "Returns no data, so it is the safe way to find the version a write needs.",
                        "{\"type\":\"object\",\"properties\":{" + PATH_PROP + "},"
                                + "\"required\":[\"path\"],\"additionalProperties\":false}", this::statNode),
                tool("zookeeper_get_node", "Data and metadata of one znode. Node data may hold credentials, "
                                + "so treat this as a disclosure.",
                        "{\"type\":\"object\",\"properties\":{" + PATH_PROP + "},"
                                + "\"required\":[\"path\"],\"additionalProperties\":false}", this::getNode),
                tool("zookeeper_create_node", "Create one persistent znode with the given data.",
                        "{\"type\":\"object\",\"properties\":{" + PATH_PROP
                                + ",\"data\":{\"type\":\"string\",\"maxLength\":262144}},"
                                + "\"required\":[\"path\"],\"additionalProperties\":false}", this::createNode),
                tool("zookeeper_set_data", "Replace the data of one znode. Requires the version currently "
                                + "expected; -1 writes regardless of what is there.",
                        "{\"type\":\"object\",\"properties\":{" + PATH_PROP + "," + VERSION_PROP
                                + ",\"data\":{\"type\":\"string\",\"maxLength\":262144}},"
                                + "\"required\":[\"path\",\"version\"],\"additionalProperties\":false}", this::setData),
                tool("zookeeper_delete_node", "Delete one znode. It must have no children, and the version "
                                + "must match; -1 deletes regardless of what is there.",
                        "{\"type\":\"object\",\"properties\":{" + PATH_PROP + "," + VERSION_PROP + "},"
                                + "\"required\":[\"path\",\"version\"],\"additionalProperties\":false}", this::deleteNode));
    }

    @FunctionalInterface
    private interface Op {
        ToolResult run(Map<String, Object> args) throws Exception;
    }

    private static SourcedTool tool(String name, String description, String schema, Op op) {
        ToolDescriptor d = new ToolDescriptor(name, null, description, Json.parseObject(schema), null, null);
        return new SourcedTool(d, new ToolExecutor() {
            @Override
            public List<URI> egressTargets(Map<String, Object> backendArguments) {
                return List.of(); // the client protocol is not HTTP; the connect string is configuration
            }

            @Override
            public ToolResult execute(ExecutionContext ctx) throws ToolExecutor.ExecutionException {
                try {
                    return op.run(ctx.arguments());
                } catch (IllegalArgumentException e) {
                    // ZooKeeper's own PathUtils rejected the path. A caller error, not a backend fault.
                    return ToolResult.text("Invalid path: " + e.getMessage(), true);
                } catch (KeeperException e) {
                    return keeperError(e);
                } catch (Unavailable e) {
                    // No session could be opened: the backend, not this request, so the breaker counts it.
                    throw new ToolExecutor.ExecutionException("ZooKeeper is unavailable: " + e.getMessage(), false, true, e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ToolExecutor.ExecutionException("interrupted", false, e);
                } catch (Exception e) {
                    throw new ToolExecutor.ExecutionException(
                            "ZooKeeper call failed: " + e.getClass().getSimpleName(), false, e);
                }
            }
        });
    }

    /**
     * Maps a {@link KeeperException} onto the right kind of failure. NONODE, NODEEXISTS, BADVERSION,
     * NOTEMPTY and NOAUTH are things the caller got wrong and must not count against the circuit
     * breaker; CONNECTIONLOSS, SESSIONEXPIRED and OPERATIONTIMEOUT are the backend and must.
     */
    private static ToolResult keeperError(KeeperException e) throws ToolExecutor.ExecutionException {
        switch (e.code()) {
            case CONNECTIONLOSS, SESSIONEXPIRED, SESSIONMOVED, OPERATIONTIMEOUT ->
                    throw new ToolExecutor.ExecutionException(
                            "ZooKeeper is unavailable: " + e.code(), e.code() == KeeperException.Code.OPERATIONTIMEOUT, e);
            default -> {
                return ToolResult.text("ZooKeeper refused the request: " + e.code()
                        + (e.getPath() == null ? "" : " for " + e.getPath()), true);
            }
        }
    }

    /**
     * No ZooKeeper session could be opened (client creation failed or SyncConnected did not arrive in
     * time). The message never carries the connect string or credentials.
     */
    static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------ tools

    private ToolResult listChildren(Map<String, Object> a) throws Exception {
        String path = path(a);
        List<String> children = new ArrayList<>(client().getChildren(path, false));
        Collections.sort(children);
        boolean truncated = children.size() > maxChildren;
        if (truncated) {
            children = new ArrayList<>(children.subList(0, maxChildren));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("children", children);
        out.put("childCount", (long) children.size());
        out.put("truncated", truncated);
        if (truncated) {
            out.put("note", "stopped at maxChildren=" + maxChildren + "; list a deeper path instead of raising it");
        }
        return ToolResult.structured(Json.write(out), out);
    }

    private ToolResult statNode(Map<String, Object> a) throws Exception {
        String path = path(a);
        Stat stat = client().exists(path, false);
        if (stat == null) {
            return ToolResult.text("No such znode: " + path, true);
        }
        return ToolResult.structured(Json.write(stat(path, stat)), stat(path, stat));
    }

    private ToolResult getNode(Map<String, Object> a) throws Exception {
        String path = path(a);
        Stat stat = new Stat();
        byte[] data = client().getData(path, false, stat);
        Map<String, Object> out = stat(path, stat);
        out.putAll(renderData(data));
        return ToolResult.structured(Json.write(out), out);
    }

    private ToolResult createNode(Map<String, Object> a) throws Exception {
        String path = path(a);
        byte[] data = a.get("data") == null ? new byte[0] : String.valueOf(a.get("data")).getBytes(StandardCharsets.UTF_8);
        String created = client().create(path, data, ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
        Map<String, Object> out = Map.of("created", created, "dataBytes", (long) data.length);
        return ToolResult.structured(Json.write(out), out);
    }

    private ToolResult setData(Map<String, Object> a) throws Exception {
        String path = path(a);
        int version = version(a);
        byte[] data = a.get("data") == null ? new byte[0] : String.valueOf(a.get("data")).getBytes(StandardCharsets.UTF_8);
        Stat stat = client().setData(path, data, version);
        Map<String, Object> out = stat(path, stat);
        out.put("dataBytes", (long) data.length);
        return ToolResult.structured(Json.write(out), out);
    }

    private ToolResult deleteNode(Map<String, Object> a) throws Exception {
        String path = path(a);
        int version = version(a);
        client().delete(path, version);
        Map<String, Object> out = Map.of("deleted", path, "version", (long) version);
        return ToolResult.structured(Json.write(out), out);
    }

    // ------------------------------------------------------------------ helpers

    /** Validates with ZooKeeper's own rules, so this source cannot disagree with the server. */
    private static String path(Map<String, Object> a) {
        String path = String.valueOf(a.get("path"));
        PathUtils.validatePath(path);
        return path;
    }

    private static int version(Map<String, Object> a) {
        Long v = JsonValues.integral(a.get("version"));
        if (v == null) {
            throw new IllegalArgumentException("version is required; read the node first, or pass -1 to write regardless");
        }
        return v.intValue();
    }

    private static Map<String, Object> stat(String path, Stat s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("version", (long) s.getVersion());
        out.put("dataLength", (long) s.getDataLength());
        out.put("numChildren", (long) s.getNumChildren());
        out.put("czxid", s.getCzxid());
        out.put("mzxid", s.getMzxid());
        out.put("ephemeralOwner", s.getEphemeralOwner());
        return out;
    }

    /**
     * Znode data is bytes. Text that is valid UTF-8 is returned as text; anything else is base64 with
     * {@code encoding} set, because handing a model mojibake and asking it to reason is worse than
     * handing it something it can recognise as opaque.
     */
    private Map<String, Object> renderData(byte[] data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataBytes", (long) data.length);
        byte[] bounded = data.length <= maxDataBytes ? data : java.util.Arrays.copyOf(data, maxDataBytes);
        out.put("dataTruncated", data.length > maxDataBytes);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            out.put("data", decoder.decode(java.nio.ByteBuffer.wrap(bounded)).toString());
            out.put("encoding", "utf-8");
        } catch (CharacterCodingException e) {
            out.put("data", Base64.getEncoder().encodeToString(bounded));
            out.put("encoding", "base64");
        }
        return out;
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            try {
                client.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            client = null;
        }
    }
}
