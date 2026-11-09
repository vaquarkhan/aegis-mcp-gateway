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
/*
 * Gaps L3 and L4 at the configuration layer: team quotas (rateLimits.perGroupPerMinute), personal-data
 * redaction (output.redactPii) and the AuditSinkFactory SPI (audit.sinks) for SIEM export.
 * Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.spi.AuditSinkFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.spi.AuditSink;
import io.github.vaquarkhan.aegis.mcp.testing.TestMcpServer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ThreatModelGapConfigTest {

    static final String SINK_TYPE = "gap-test-siem";
    static final List<Map<String, Object>> RECEIVED = new CopyOnWriteArrayList<>();
    static final AtomicBoolean FAILING = new AtomicBoolean();

    /** Test SIEM sink registered in src/test/resources/META-INF/services. */
    public static final class TestSiemFactory implements AuditSinkFactory {
        @Override
        public String type() {
            return SINK_TYPE;
        }

        @Override
        public AuditSink create(ConfigNode settings, BuildContext context) {
            String index = settings.string("index", "aegis");
            return record -> {
                if (FAILING.get()) {
                    throw new java.io.IOException("SIEM unreachable");
                }
                RECEIVED.add(Map.of("index", index, "record", record));
            };
        }
    }

    @TempDir
    Path dir;

    private static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "k".repeat(40));

    private BuildContext ctx() {
        return BuildContext.defaults(dir).withEnv(ENV::get);
    }

    private static ToolCall call(String tool) {
        return new ToolCall("1", tool, Map.of(), Map.of(), "");
    }

    @Test
    void teamQuotaIsSharedByEveryMemberOfTheGroup() throws Exception {
        try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            mcp.tool("ping", "Ping.", Map.of("type", "object"), a -> TestMcpServer.textResult("pong"));
            Map<String, Object> cfg = Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"],"classes":["READ"]}]},"audit":{"file":"q.jsonl"},
                     "egress":{"allowCidrs":["127.0.0.0/8"]},
                     "rateLimits":{"perCallerPerMinute":100,"perGroupPerMinute":{"contractors":3}},
                     "sources":[{"id":"m","type":"mcp","url":"%s","defaultClass":"READ"}]}""".formatted(mcp.uri()));
            try (Gateway g = GatewayFactory.build(cfg, ctx())) {
                int admitted = 0;
                String lastCode = null;
                for (int i = 0; i < 6; i++) {
                    // Six different identities in the same team: per-caller limits alone would admit all six.
                    CallerIdentity member = CallerIdentity.of("c" + i, Set.of("contractors"), "idp", "jwt");
                    CallOutcome o = g.engine().call(member, call("ping"));
                    admitted += o.allowed() ? 1 : 0;
                    lastCode = o.allowed() ? lastCode : o.decision().code();
                }
                assertEquals(3, admitted);
                assertEquals(DenyCodes.RATE_LIMITED, lastCode);
                assertTrue(g.engine().call(CallerIdentity.of("e1", Set.of("employees"), "idp", "jwt"), call("ping")).allowed(),
                        "other teams keep their own budget");
            }
            Map<String, Object> bad = Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"q2.jsonl"},
                     "rateLimits":{"perGroupPerMinute":{"contractors":0}},
                     "sources":[{"id":"m","type":"mcp","url":"%s"}]}""".formatted(mcp.uri()));
            assertThrows(ConfigException.class, () -> GatewayFactory.build(bad, ctx()));
        }
        System.out.println("THREATMODEL|GAP-L3b|ContextForge team rate limit parity|RateLimiter.perGroupPerMinute|FIXED|"
                + "6 identities in one group share a 3/min budget; other groups unaffected; 0 is a config error");
    }

    @Test
    void personalDataIsRedactedWhenRedactPiiIsOn() throws Exception {
        String leak = "contact jane.doe@example.com or +14155550123, card 4111 1111 1111 1111, ssn 123-45-6789, order 98765";
        try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            mcp.tool("profile", "Profile.", Map.of("type", "object"), a -> TestMcpServer.textResult(leak));
            String base = """
                    {"policy":{"rules":[{"id":"r","groups":["*"],"classes":["READ"]}]},"audit":{"file":"%s"},
                     "egress":{"allowCidrs":["127.0.0.0/8"]},%s
                     "sources":[{"id":"m","type":"mcp","url":"%s","defaultClass":"READ"}]}""";
            CallerIdentity alice = CallerIdentity.of("alice", Set.of("analysts"), "idp", "jwt");
            try (Gateway g = GatewayFactory.build(Json.parseObject(base.formatted("p1.jsonl", "", mcp.uri())), ctx())) {
                String out = Json.write(g.engine().call(alice, call("profile")).result().body());
                assertTrue(out.contains("jane.doe@example.com"), "off by default outside complete control");
            }
            try (Gateway g = GatewayFactory.build(Json.parseObject(base.formatted("p2.jsonl",
                    "\"output\":{\"redactPii\":true},", mcp.uri())), ctx())) {
                String out = Json.write(g.engine().call(alice, call("profile")).result().body());
                assertFalse(out.contains("jane.doe@example.com"), out);
                assertFalse(out.contains("4155550123"), out);
                assertFalse(out.contains("4111 1111 1111 1111"), out);
                assertFalse(out.contains("123-45-6789"), out);
                assertTrue(out.contains("order 98765"), "short numbers are left alone: " + out);
            }
        }
        System.out.println("THREATMODEL|GAP-L4c|ContextForge PII filter plugin parity; OWASP LLM02 sensitive disclosure|"
                + "OutputGuard.PII_REDACTIONS + output.redactPii|FIXED|email, E.164 phone, card, SSN redacted; on by default in complete control");
    }

    @Test
    void signedCheckpointsDetectARegeneratedCheckpointFile() throws Exception {
        String[] keys = io.github.vaquarkhan.aegis.engine.catalog.SignedCatalogManifest.generateKeyPair();
        java.nio.file.Files.writeString(dir.resolve("cp.key"), keys[0]);
        java.nio.file.Files.writeString(dir.resolve("cp.pub"), keys[1]);
        try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            mcp.tool("ping", "Ping.", Map.of("type", "object"), a -> TestMcpServer.textResult("pong"));
            Map<String, Object> cfg = Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"],"classes":["READ"]}]},
                     "audit":{"file":"c.jsonl","checkpoint":{"file":"c.ckpt","everyRecords":1,"signingKeyFile":"cp.key"}},
                     "egress":{"allowCidrs":["127.0.0.0/8"]},
                     "sources":[{"id":"m","type":"mcp","url":"%s","defaultClass":"READ"}]}""".formatted(mcp.uri()));
            CallerIdentity alice = CallerIdentity.of("alice", Set.of("analysts"), "idp", "jwt");
            try (Gateway g = GatewayFactory.build(cfg, ctx())) {
                assertTrue(g.engine().call(alice, call("ping")).allowed());
            }
            java.security.PublicKey pub = io.github.vaquarkhan.aegis.engine.catalog.SignedCatalogManifest.readPublicKey(dir.resolve("cp.pub"));
            var ok = io.github.vaquarkhan.aegis.engine.audit.AuditCheckpointVerifier.verify(dir.resolve("c.jsonl"), dir.resolve("c.ckpt"), pub);
            assertTrue(ok.ok(), String.valueOf(ok.error()));
            assertTrue(java.nio.file.Files.readString(dir.resolve("c.ckpt")).contains("\"signature\""));
            // An attacker who rewrites the journal must also regenerate the checkpoint file; without the
            // signing key the regenerated lines carry no valid signature.
            java.util.List<String> lines = java.nio.file.Files.readAllLines(dir.resolve("c.ckpt"));
            String forged = lines.get(lines.size() - 1).replaceAll("\"signature\":\"[^\"]*\"", "\"signature\":\"AAAA\"");
            java.nio.file.Files.writeString(dir.resolve("c.ckpt"), String.join("\n", lines.subList(0, lines.size() - 1)) + "\n" + forged + "\n");
            var broken = io.github.vaquarkhan.aegis.engine.audit.AuditCheckpointVerifier.verify(dir.resolve("c.jsonl"), dir.resolve("c.ckpt"), pub);
            assertFalse(broken.ok());
            assertTrue(broken.error().contains("signature"), broken.error());
            assertThrows(ConfigException.class, () -> GatewayFactory.build(Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"]}]},
                     "audit":{"file":"c2.jsonl","checkpoint":{"file":"c2.ckpt","signingKeyFile":"missing.key"}},
                     "sources":[{"id":"m","type":"mcp","url":"%s"}]}""".formatted(mcp.uri())), ctx()));
        }
        System.out.println("THREATMODEL|SCAN-O11|AI scanner readiness O-11|AuditCheckpointPolicy.signingKey + AuditCheckpointVerifier|FIXED|"
                + "every checkpoint line is Ed25519-signed; a regenerated checkpoint file without the key fails verification");
    }

    @Test
    void auditSinkSpiReceivesEveryRecordAndFailsClosed() throws Exception {
        RECEIVED.clear();
        FAILING.set(false);
        try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            mcp.tool("ping", "Ping.", Map.of("type", "object"), a -> TestMcpServer.textResult("pong"));
            Map<String, Object> cfg = Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"],"classes":["READ"]}]},
                     "audit":{"file":"s.jsonl","sinks":[{"type":"%s","settings":{"index":"mcp-audit"}}]},
                     "egress":{"allowCidrs":["127.0.0.0/8"]},
                     "sources":[{"id":"m","type":"mcp","url":"%s","defaultClass":"READ"}]}""".formatted(SINK_TYPE, mcp.uri()));
            CallerIdentity alice = CallerIdentity.of("alice", Set.of("analysts"), "idp", "jwt");
            try (Gateway g = GatewayFactory.build(cfg, ctx())) {
                assertTrue(g.engine().call(alice, call("ping")).allowed());
                assertFalse(RECEIVED.isEmpty(), "the SIEM sink received the decision record");
                assertEquals("mcp-audit", RECEIVED.get(0).get("index"));
                FAILING.set(true);
                CallOutcome down = g.engine().call(alice, call("ping"));
                assertFalse(down.allowed(), "an unrecorded call is not made: " + down.decision());
                assertEquals(DenyCodes.AUDIT_UNAVAILABLE, down.decision().code());
            } finally {
                FAILING.set(false);
            }
            Map<String, Object> unknown = Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"s2.jsonl","sinks":[{"type":"nope"}]},
                     "sources":[{"id":"m","type":"mcp","url":"%s"}]}""".formatted(mcp.uri()));
            assertThrows(ConfigException.class, () -> GatewayFactory.build(unknown, ctx()));
            Map<String, Object> typo = Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"]}]},
                     "audit":{"file":"s3.jsonl","sinks":[{"type":"%s","settings":{"indx":"x"}}]},
                     "sources":[{"id":"m","type":"mcp","url":"%s"}]}""".formatted(SINK_TYPE, mcp.uri()));
            assertThrows(ConfigException.class, () -> GatewayFactory.build(typo, ctx()), "unknown sink settings keys fail startup");
        }
        System.out.println("THREATMODEL|GAP-L4d|ContextForge SIEM export parity|AuditSinkFactory SPI + audit.sinks|FIXED|"
                + "every record reaches the SIEM sink after the journal; a sink failure denies the call (AUDIT_UNAVAILABLE)");
    }
}
