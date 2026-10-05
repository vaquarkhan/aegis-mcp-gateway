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
package io.github.vaquarkhan.aegis.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.audit.AuditChainVerifier;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.mcp.testing.TestMcpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads each shipped example configuration UNCHANGED except for the backend address (pointed at a
 * contract harness) and the private network CIDR (127.0.0.0/8 instead of 10.0.0.0/8), then runs the
 * governance flows an operator would rely on.
 */
class ExampleConfigsTest {

    static final String FLINK_JOB = "0123456789abcdef0123456789abcdef";
    static final String FLINK_TRIGGER = "fedcba9876543210fedcba9876543210";
    /**
     * Jar ids in the shape Flink really assigns: the JobManager stores an upload as
     * {@code <uuid>_<original file name>} and that whole string is the jar id. Using the real shape
     * here is what makes the {@code run-approved-jars} pattern in {@code flink.yaml} meaningful; an
     * invented id such as "approved-etl.jar" would pass a prefix pattern that no real jar can match.
     */
    static final String FLINK_APPROVED_JAR = "da41fdf4-c363-469c-844d-6429fe0aec04_approved-etl.jar";
    static final String FLINK_PLAIN_JAR = "da41fdf4-c363-469c-844d-6429fe0aec04_etl.jar";
    static final Map<String, String> ENV = Map.of(
            "AEGIS_SIGNING_KEY", "example-signing-key-0123456789-abcdefghij",
            "ICEBERG_CATALOG_TOKEN", "iceberg-service-token",
            "ENGINE_MCP_TOKEN", "engine-service-token");

    @TempDir
    Path dir;

    static CallerIdentity who(String name, String... groups) {
        return CallerIdentity.of(name, Set.of(groups), "https://idp.example", "jwt");
    }

    Gateway load(String profile, Map<String, String> replace) throws Exception {
        String yaml = Files.readString(AdopterConfigurations.profile(profile));
        Map<String, String> all = new LinkedHashMap<>(replace);
        all.put("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]");
        for (Map.Entry<String, String> e : all.entrySet()) {
            assertTrue(yaml.contains(e.getKey()), profile + " should contain " + e.getKey());
            yaml = yaml.replace(e.getKey(), e.getValue());
        }
        Path p = dir.resolve(profile + ".yaml");
        Files.writeString(p, yaml);
        return GatewayFactory.build(p, BuildContext.defaults(dir).withEnv(ENV::get));
    }

    static CallOutcome call(Gateway g, CallerIdentity c, String tool, Map<String, Object> args) {
        return g.engine().call(c, new ToolCall("1", tool, args, Map.of(), "2026-07-28"));
    }

    static CallOutcome call(Gateway g, CallerIdentity c, String tool, Map<String, Object> args, Map<String, Object> meta) {
        return g.engine().call(c, new ToolCall("1", tool, args, meta, "2026-07-28"));
    }

    /** Validate (dry run) -> approve -> promote, returning the promoted outcome. */
    static CallOutcome validateApprovePromote(Gateway g, CallerIdentity requester, CallerIdentity approver, String tool,
                                              Map<String, Object> args, boolean destructive) {
        CallOutcome dry = call(g, requester, tool, args, Map.of(ControlTokens.META_DRY_RUN, true));
        assertTrue(dry.allowed(), "dry run: " + dry.decision());
        String rid = (String) dry.governance().get("approvalRequestId");
        assertNotNull(rid, "dry run opens an approval request");
        g.engine().approvals().decide(rid, approver, true);
        String token = (String) g.engine().approvals().get(rid, requester).get("token");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(ControlTokens.META_APPROVAL, token);
        if (destructive) {
            String receipt = (String) dry.governance().get("dryRunReceipt");
            assertNotNull(receipt, "destructive dry run issues a receipt");
            meta.put(ControlTokens.META_RECEIPT, receipt);
        }
        return call(g, requester, tool, args, meta);
    }

    static String text(CallOutcome o) {
        return Json.write(o.toMcpResult());
    }

    @Test
    void flink() throws Exception {
        try (RestRouteContractHarness m = RestRouteContractHarness.flink();
             Gateway g = load("flink", Map.of("http://flink-jobmanager:8081", m.url()))) {
            CallerIdentity user = who("ana", "flink-users");
            CallerIdentity op = who("oscar", "flink-operators");
            CallerIdentity approver = who("petra", "flink-approvers");

            CallOutcome list = call(g, user, "flink_list_jobs", Map.of());
            assertTrue(list.allowed() && text(list).contains("orders-etl"), text(list));
            assertEquals("GET /jobs/overview", m.last());
            assertEquals(DenyCodes.INVALID_INPUT, call(g, user, "flink_get_job", Map.of("jobId", "short")).decision().code());
            assertEquals(DenyCodes.READONLY_CALLER, call(g, user, "flink_cancel_job", Map.of("jobId", FLINK_JOB)).decision().code());

            // A nested URL argument aimed at cloud metadata is stopped by egress before Flink sees it.
            CallOutcome ssrf = call(g, op, "flink_trigger_savepoint",
                    Map.of("jobId", FLINK_JOB, "targetDirectory", "http://169.254.169.254/latest/meta-data"));
            assertEquals(DenyCodes.EGRESS_DENIED, ssrf.decision().code());

            CallOutcome sp = validateApprovePromote(g, op, approver, "flink_trigger_savepoint",
                    Map.of("jobId", FLINK_JOB, "targetDirectory", "s3://savepoints/orders"), false);
            assertTrue(sp.allowed(), sp.decision().toString());
            assertEquals("POST /jobs/" + FLINK_JOB + "/savepoints body={\"target-directory\":\"s3://savepoints/orders\",\"cancel-job\":false}", m.last());

            // Triggering a savepoint only returns a request-id, so the status read is what closes the
            // loop: without it an agent cannot find out whether the savepoint actually completed.
            String trigger = Json.write(sp.toMcpResult());
            assertTrue(trigger.contains(FLINK_TRIGGER), trigger);
            CallOutcome spStatus = call(g, user, "flink_get_savepoint_status",
                    Map.of("jobId", FLINK_JOB, "triggerId", FLINK_TRIGGER));
            assertTrue(spStatus.allowed(), spStatus.decision().toString());
            assertTrue(text(spStatus).contains("COMPLETED"), text(spStatus));
            assertEquals("GET /jobs/" + FLINK_JOB + "/savepoints/" + FLINK_TRIGGER, m.last());

            int before = m.requests.size();
            CallOutcome cancel = validateApprovePromote(g, op, approver, "flink_cancel_job", Map.of("jobId", FLINK_JOB), true);
            assertTrue(cancel.allowed(), cancel.decision().toString());
            assertEquals(before + 1, m.requests.size(), "GATEWAY dry run sent nothing; only the promote reached Flink");
            assertEquals("PATCH /jobs/" + FLINK_JOB + "?mode=cancel", m.last());
        }
        assertTrue(AuditChainVerifier.verify(dir.resolve("audit/aegis-flink.jsonl")).ok());
    }

    @Test
    void flinkCoversTheDeeperJobAndJarSurface() throws Exception {
        // The 0.1 Flink adapter carried 19 tools and the first 2.0 config carried 4. These are the tools
        // added to close that gap, each called for real against the documented JobManager endpoints.
        try (RestRouteContractHarness m = RestRouteContractHarness.flink();
             Gateway g = load("flink", Map.of("http://flink-jobmanager:8081", m.url()))) {
            CallerIdentity user = who("ana", "flink-users");
            CallerIdentity op = who("oscar", "flink-operators");
            CallerIdentity approver = who("petra", "flink-approvers");

            Map<String, Map<String, Object>> reads = new LinkedHashMap<>();
            reads.put("flink_get_cluster_info", Map.of());
            reads.put("flink_list_taskmanagers", Map.of());
            reads.put("flink_get_flink_config", Map.of());
            reads.put("flink_list_jars", Map.of());
            reads.put("flink_get_job_status", Map.of("jobId", FLINK_JOB));
            reads.put("flink_get_job_exceptions", Map.of("jobId", FLINK_JOB));
            reads.put("flink_get_job_metrics", Map.of("jobId", FLINK_JOB));
            reads.put("flink_get_job_config", Map.of("jobId", FLINK_JOB));
            reads.put("flink_list_checkpoints", Map.of("jobId", FLINK_JOB));
            reads.put("flink_get_savepoint_status", Map.of("jobId", FLINK_JOB, "triggerId", FLINK_TRIGGER));
            for (Map.Entry<String, Map<String, Object>> e : reads.entrySet()) {
                CallOutcome o = call(g, user, e.getKey(), e.getValue());
                assertTrue(o.allowed(), e.getKey() + " -> " + o.decision());
                assertFalse(o.result().isError(), e.getKey() + " -> " + text(o) + " (sent: " + m.last() + ")");
            }
            // flink_get_job_status narrows the same endpoint to the job state.
            assertTrue(text(call(g, user, "flink_get_job_status", Map.of("jobId", FLINK_JOB))).contains("RUNNING"));

            // Rescale is a MUTATE: refused to a user, allowed to an operator after approval.
            assertEquals(DenyCodes.READONLY_CALLER,
                    call(g, user, "flink_rescale_job", Map.of("jobId", FLINK_JOB, "parallelism", 4L)).decision().code());
            CallOutcome rescale = validateApprovePromote(g, op, approver, "flink_rescale_job",
                    Map.of("jobId", FLINK_JOB, "parallelism", 4L), false);
            // /rescaling, not /rescale: the latter is not a Flink route and answers 404.
            assertTrue(rescale.allowed(), rescale.decision().toString());
            assertEquals("PATCH /jobs/" + FLINK_JOB + "/rescaling?parallelism=4", m.last());

            // Stop with a savepoint is DESTRUCTIVE and validates against the real job first (TOOL mode),
            // so the dry run sends a GET and the promote sends the POST.
            int before = m.requests.size();
            CallOutcome stop = validateApprovePromote(g, op, approver, "flink_stop_job",
                    Map.of("jobId", FLINK_JOB, "targetDirectory", "s3://savepoints/stop"), true);
            assertTrue(stop.allowed(), stop.decision().toString());
            assertEquals(before + 2, m.requests.size(), "TOOL dry run read the job, then the promote stopped it");
            assertTrue(m.requests.get(before).startsWith("GET /jobs/" + FLINK_JOB), m.requests.get(before));
            assertTrue(m.last().startsWith("POST /jobs/" + FLINK_JOB + "/stop"), m.last());

            // Running a jar starts code on the cluster, so the jar itself is allow-listed by policy.
            assertEquals(DenyCodes.POLICY_DENIED,
                    call(g, op, "flink_run_jar", Map.of("jarId", FLINK_PLAIN_JAR)).decision().code(),
                    "a jar outside the approved pattern is refused at step 3");
            CallOutcome run = validateApprovePromote(g, op, approver, "flink_run_jar",
                    Map.of("jarId", FLINK_APPROVED_JAR, "entryClass", "com.example.Etl", "parallelism", 2L), true);
            assertTrue(run.allowed(), run.decision().toString());
            assertTrue(m.last().startsWith("POST /jars/" + FLINK_APPROVED_JAR + "/run"), m.last());
            assertTrue(m.last().contains("\\\"entryClass\\\":\\\"com.example.Etl\\\"")
                    || m.last().contains("\"entryClass\":\"com.example.Etl\""), m.last());
        }
    }

    @Test
    void spark() throws Exception {
        try (RestRouteContractHarness m = RestRouteContractHarness.spark();
             Gateway g = load("spark", Map.of("http://spark-history:18080", m.url(), "http://spark-master:6066", m.url()))) {
            CallerIdentity user = who("ana", "spark-users");
            CallerIdentity op = who("oscar", "spark-operators");
            CallerIdentity approver = who("petra", "spark-approvers");

            CallOutcome apps = call(g, user, "spark_list_applications", Map.of("status", "running", "limit", 5L));
            assertTrue(apps.allowed() && text(apps).contains("daily-etl"), text(apps));
            assertEquals("GET /api/v1/applications?status=running&limit=5", m.last());
            assertEquals(DenyCodes.INVALID_INPUT, call(g, user, "spark_list_applications", Map.of("status", "everything")).decision().code());
            assertEquals(DenyCodes.SCOPE_DENIED, call(g, user, "spark_list_jobs", Map.of("appId", "app-1/../../admin")).decision().code());
            CallOutcome jobs = call(g, user, "spark_list_jobs", Map.of("appId", "app-20260926-0001"));
            assertTrue(jobs.allowed());
            assertEquals(DenyCodes.READONLY_CALLER, call(g, user, "spark_kill_submission", Map.of("submissionId", "driver-1")).decision().code());
            CallOutcome kill = validateApprovePromote(g, op, approver, "spark_kill_submission", Map.of("submissionId", "driver-1"), true);
            assertTrue(kill.allowed(), kill.decision().toString());
            assertEquals("POST /v1/submissions/kill/driver-1", m.last());
        }
    }

    @Test
    void iceberg() throws Exception {
        try (RestRouteContractHarness m = RestRouteContractHarness.iceberg();
             Gateway g = load("iceberg", Map.of("http://iceberg-rest:8181", m.url()))) {
            CallerIdentity analyst = who("ana", "analysts");
            CallerIdentity eng = who("eve", "data-engineers");
            CallerIdentity steward = who("sten", "data-stewards");

            CallOutcome tables = call(g, analyst, "iceberg_list_tables", Map.of("namespace", "sales"));
            assertTrue(tables.allowed(), tables.decision().toString());
            assertEquals("GET /v1/namespaces/sales/tables auth=Bearer iceberg-service-token", m.last(),
                    "the catalog sees the gateway's service token, never the caller's");
            assertEquals(DenyCodes.SCOPE_DENIED, call(g, analyst, "iceberg_list_tables", Map.of("namespace", "finance")).decision().code());

            CallOutcome load = call(g, analyst, "iceberg_load_table", Map.of("namespace", "sales", "table", "orders"));
            assertTrue(text(load).contains("\"fields\"") && !text(load).contains("metadata-location"), "response pointer selects schemas");

            assertEquals(DenyCodes.POLICY_DENIED, call(g, eng, "iceberg_drop_table", Map.of("namespace", "finance", "table", "ledger")).decision().code());

            int before = m.requests.size();
            CallOutcome drop = validateApprovePromote(g, eng, steward, "iceberg_drop_table", Map.of("namespace", "sales", "table", "orders"), true);
            assertTrue(drop.allowed(), drop.decision().toString());
            assertEquals(before + 2, m.requests.size(), "TOOL dry run loaded the table, then the promote dropped it");
            assertTrue(m.requests.get(before).startsWith("GET /v1/namespaces/sales/tables/orders"));
            assertTrue(m.last().startsWith("DELETE /v1/namespaces/sales/tables/orders?purgeRequested=true"));

            CallOutcome ns = validateApprovePromote(g, eng, steward, "iceberg_create_namespace", Map.of("namespace", "new_ns"), false);
            assertTrue(ns.allowed(), ns.decision().toString());
            assertTrue(m.last().startsWith("POST /v1/namespaces body={\"namespace\":[\"new_ns\"],\"properties\":{}}"), m.last());
        }
    }

    @Test
    void kafkaConnectAndAdminClient() throws Exception {
        // The kafka-admin source talks to a real Kafka 3.9.1 KRaft broker in its own JVM; every topic
        // assertion below reads the broker's own metadata through a separate stock Admin client.
        // Broker data lives under target/, not @TempDir: on Windows the broker's memory-mapped index files
        // of the deleted topic can stay locked briefly after the process exits, which fails TempDir cleanup.
        try (RealKafkaBroker broker = RealKafkaBroker.start(Path.of("target", "kafka-admin-broker-" + System.nanoTime()).toAbsolutePath());
             org.apache.kafka.clients.admin.Admin admin = broker.admin();
             RestRouteContractHarness m = RestRouteContractHarness.kafkaConnect();
             Gateway g = load("kafka", Map.of("http://kafka-connect:8083", m.url(), "bootstrapServers: kafka-1:9092",
                     "bootstrapServers: " + broker.bootstrap()))) {
            CallerIdentity user = who("ana", "kafka-users");
            CallerIdentity op = who("oscar", "kafka-operators");
            CallerIdentity approver = who("petra", "kafka-approvers");

            assertTrue(text(call(g, user, "connect_list_connectors", Map.of())).contains("jdbc-sink"));
            CallOutcome pause = validateApprovePromote(g, op, approver, "connect_pause_connector", Map.of("name", "jdbc-sink"), false);
            assertTrue(pause.allowed(), pause.decision().toString());
            assertEquals("PUT /connectors/jdbc-sink/pause", m.last());
            CallOutcome del = validateApprovePromote(g, op, approver, "connect_delete_connector", Map.of("name", "s3-sink"), true);
            assertTrue(del.allowed(), del.decision().toString());
            assertEquals("DELETE /connectors/s3-sink", m.last());

            // Admin client source against the real broker.
            Map<String, Object> newTopic = Map.of("topic", "orders.v2", "partitions", 3L, "replicationFactor", 1L);
            CallOutcome dry = call(g, op, "kafka_create_topic", newTopic, Map.of(ControlTokens.META_DRY_RUN, true));
            assertTrue(dry.allowed() && text(dry).contains("Validated (not created)"), text(dry));
            assertFalse(admin.listTopics().names().get().contains("orders.v2"),
                    "the broker validated CreateTopics with validateOnly=true and created nothing");
            // The broker's own validation is what the dry run reports: an impossible replication factor is refused.
            CallOutcome impossible = call(g, op, "kafka_create_topic",
                    Map.of("topic", "orders.v3", "partitions", 1L, "replicationFactor", 3L), Map.of(ControlTokens.META_DRY_RUN, true));
            assertTrue(!impossible.allowed() || impossible.result().isError(), "a single broker cannot hold 3 replicas: " + text(impossible));
            assertFalse(admin.listTopics().names().get().contains("orders.v3"));
            String rid = (String) dry.governance().get("approvalRequestId");
            g.engine().approvals().decide(rid, approver, true);
            String token = (String) g.engine().approvals().get(rid, op).get("token");
            CallOutcome create = call(g, op, "kafka_create_topic", newTopic, Map.of(ControlTokens.META_APPROVAL, token));
            assertTrue(create.allowed(), create.decision().toString());
            assertTrue(eventually(() -> admin.listTopics().names().get().contains("orders.v2")), "the approved call is a real create");
            assertEquals(3, admin.describeTopics(List.of("orders.v2")).allTopicNames().get().get("orders.v2").partitions().size());
            CallOutcome listed = call(g, user, "kafka_list_topics", Map.of());
            assertTrue(text(listed).contains("orders.v2"));
            CallOutcome cluster = call(g, user, "kafka_describe_cluster", Map.of());
            assertTrue(cluster.allowed() && text(cluster).contains("clusterId"), text(cluster));
            CallOutcome described = call(g, user, "kafka_describe_topic", Map.of("topic", "orders.v2"));
            assertTrue(text(described).contains("\"partitions\":3"), text(described));
            CallOutcome configs = call(g, user, "kafka_describe_topic_configs", Map.of("topic", "orders.v2"));
            assertTrue(configs.allowed() && text(configs).contains("configs"), text(configs));

            assertEquals(DenyCodes.POLICY_DENIED, call(g, op, "kafka_delete_topic", Map.of("topic", "__consumer_offsets")).decision().code());
            CallOutcome dropped = validateApprovePromote(g, op, approver, "kafka_delete_topic", Map.of("topic", "orders.v2"), true);
            assertTrue(dropped.allowed(), dropped.decision().toString());
            // The controller has accepted the deletion when the call returns; the broker applies it to its
            // metadata cache shortly after, so a listTopics in the same millisecond can still show it.
            assertTrue(eventually(() -> !admin.listTopics().names().get().contains("orders.v2")),
                    "the broker's metadata no longer has the topic");
        }
    }

    /**
     * Polls a real backend's own state for up to 30 seconds. Kafka may accept a socket before the
     * controller assigns the first node, so a temporary AdminClient exception is retried too. If the
     * backend never recovers, preserve the final exception instead of converting it to a false result.
     */
    private static boolean eventually(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.call()) {
                    return true;
                }
                last = null;
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(200);
        }
        if (last != null) {
            throw last;
        }
        return condition.call();
    }

    @Test
    void sparkIcebergAndKafkaCoverTheirDeeperSurfaceToo() throws Exception {
        // The rest of the tools added to close the 0.1 gap. Declared is not enough: each one is called.
        try (RestRouteContractHarness m = RestRouteContractHarness.spark();
             Gateway g = load("spark", Map.of("http://spark-history:18080", m.url(), "http://spark-master:6066", m.url()))) {
            CallerIdentity user = who("ana", "spark-users");
            CallerIdentity op = who("oscar", "spark-operators");
            CallerIdentity approver = who("petra", "spark-approvers");
            String app = "app-20260926-0001";
            for (String tool : List.of("spark_get_application", "spark_list_stages", "spark_list_executors",
                    "spark_get_environment")) {
                CallOutcome o = call(g, user, tool, Map.of("appId", app));
                assertTrue(o.allowed() && !o.result().isError(), tool + " -> " + text(o) + " sent " + m.last());
            }
            assertTrue(text(call(g, user, "spark_submission_status", Map.of("submissionId", "driver-1")))
                    .contains("RUNNING"));
            // Submitting a jar is DESTRUCTIVE and the jar is allow-listed by policy, not the action.
            assertEquals(DenyCodes.POLICY_DENIED, call(g, op, "spark_submit_batch",
                    Map.of("appResource", "hdfs:///tmp/anything.jar", "mainClass", "X")).decision().code());
            CallOutcome submit = validateApprovePromote(g, op, approver, "spark_submit_batch",
                    Map.of("appResource", "hdfs:///approved/etl.jar", "mainClass", "com.example.Etl"), true);
            assertTrue(submit.allowed(), submit.decision().toString());
            assertTrue(m.last().startsWith("POST /v1/submissions/create"), m.last());
            assertTrue(m.last().contains("CreateSubmissionRequest"), m.last());
        }

        try (RestRouteContractHarness m = RestRouteContractHarness.iceberg();
             Gateway g = load("iceberg", Map.of("http://iceberg-rest:8181", m.url()))) {
            CallerIdentity analyst = who("ana", "analysts");
            CallerIdentity eng = who("eve", "data-engineers");
            CallerIdentity steward = who("sten", "data-stewards");
            // get_table_metadata is the same endpoint as load_table with a wider pointer.
            CallOutcome meta = call(g, analyst, "iceberg_get_table_metadata", Map.of("namespace", "sales", "table", "orders"));
            assertTrue(meta.allowed() && text(meta).contains("format-version"), text(meta));
            CallOutcome props = validateApprovePromote(g, eng, steward, "iceberg_set_table_properties",
                    Map.of("namespace", "sales", "table", "orders",
                            "properties", Map.of("write.metadata.delete-after-commit.enabled", "true")), false);
            assertTrue(props.allowed(), props.decision().toString());
            assertTrue(m.last().startsWith("POST /v1/namespaces/sales/tables/orders"), m.last());
            assertTrue(m.last().contains("set-properties"), m.last());
        }

        try (RestRouteContractHarness m = RestRouteContractHarness.kafkaConnect()
                .route("GET", "/connectors/[^/]+/config", 200, "{\"connector.class\":\"JdbcSinkConnector\"}")
                .route("PUT", "/connectors/[^/]+/resume", 202, "")
                .route("POST", "/connectors/[^/]+/restart", 200, "{\"name\":\"jdbc-sink\"}");
             Gateway g = load("kafka", Map.of("http://kafka-connect:8083", m.url()))) {
            CallerIdentity user = who("ana", "kafka-users");
            CallerIdentity op = who("oscar", "kafka-operators");
            CallerIdentity approver = who("petra", "kafka-approvers");
            assertTrue(text(call(g, user, "connect_connector_config", Map.of("name", "jdbc-sink")))
                    .contains("JdbcSinkConnector"));
            assertTrue(validateApprovePromote(g, op, approver, "connect_resume_connector",
                    Map.of("name", "jdbc-sink"), false).allowed());
            assertEquals("PUT /connectors/jdbc-sink/resume", m.last());
            assertTrue(validateApprovePromote(g, op, approver, "connect_restart_connector",
                    Map.of("name", "jdbc-sink", "includeTasks", true), false).allowed());
            assertTrue(m.last().startsWith("POST /connectors/jdbc-sink/restart?includeTasks=true"), m.last());
        }
    }

    @Test
    void existingMcpServerOverHttpAndStdio() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace('\\', '/');
        String cp = System.getProperty("java.class.path").replace('\\', '/');
        try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.LEGACY_SESSION)) {
            mcp.tool("list_jobs", "List jobs.", Map.of("type", "object"), a -> TestMcpServer.textResult("job-1"));
            mcp.tool("delete_job", "Delete a job.", Map.of("type", "object", "properties", Map.of("jobId", Map.of("type", "string"))),
                    a -> TestMcpServer.textResult("deleted " + a.get("jobId")));
            mcp.tool("reindex_all", "Undocumented maintenance tool.", Map.of("type", "object"), a -> TestMcpServer.textResult("done"));
            String stdioCmd = "command: [\"" + java + "\", \"-cp\", \"" + cp + "\", \"io.github.vaquarkhan.aegis.mcp.testing.TestStdioMcpServer\", \"modern\"]";
            try (Gateway g = load("existing-mcp-server", Map.of(
                    "http://engine-mcp:9000/mcp", mcp.uri().toString(),
                    "command: [node, /opt/mcp/filesystem-server/index.js, /data/reports]", stdioCmd,
                    "read_text_file: {class: READ", "echo: {class: READ"))) {
                CallerIdentity analyst = who("ana", "analysts");
                CallerIdentity op = who("oscar", "operators");
                CallerIdentity approver = who("petra", "approvers");
                List<String> names = g.engine().listTools(analyst).stream().map(t -> (String) t.get("name")).toList();
                assertTrue(names.containsAll(List.of("engine_list_jobs", "echo")), names.toString());
                assertFalse(names.contains("engine_reindex_all"), "unclassified tool defaults to DESTRUCTIVE: hidden from analysts");
                assertFalse(names.contains("env_probe"), "unlistedTools: HIDE on the stdio source");

                assertTrue(call(g, analyst, "engine_list_jobs", Map.of()).allowed());
                assertEquals("Bearer engine-service-token", mcp.receivedHeaders.get(mcp.receivedHeaders.size() - 1).get("authorization"));
                // The stdio tool is governed by the resource rule configured for it (file:path at /path).
                assertEquals(DenyCodes.SCOPE_DENIED, call(g, analyst, "echo", Map.of("text", "hi")).decision().code());
                assertTrue(text(call(g, analyst, "echo", Map.of("text", "hi", "path", "reports/q3.txt"))).contains("echo:hi (modern)"));
                CallOutcome del = validateApprovePromote(g, op, approver, "engine_delete_job", Map.of("jobId", "j9"), true);
                assertTrue(del.allowed() && text(del).contains("deleted j9"), text(del));
            }
            assertTrue(Files.exists(dir.resolve("state/pins.json")), "TOFU pins persisted");
        }
    }

    @Test
    void templateLoadsOnceFilledIn() throws Exception {
        try (RestRouteContractHarness m = new RestRouteContractHarness().route("GET", "/api/things", 200, "{\"things\":[]}")) {
            String yaml = Files.readString(AdopterConfigurations.template())
                    .replace("http://<host>:<port>", m.url()).replace("<project>", "demo");
            yaml = yaml.replace("allowCidrs: []", "allowCidrs: [127.0.0.0/8]");
            Path p = dir.resolve("demo.yaml");
            Files.writeString(p, yaml);
            try (Gateway g = GatewayFactory.build(p, BuildContext.defaults(dir).withEnv(ENV::get))) {
                assertTrue(call(g, who("u", "demo-users"), "demo_list_things", Map.of()).allowed());
            }
        }
    }
}
