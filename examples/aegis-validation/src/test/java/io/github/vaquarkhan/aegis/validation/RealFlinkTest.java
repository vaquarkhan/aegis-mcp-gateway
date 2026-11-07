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
package io.github.vaquarkhan.aegis.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The canonical adopters/configurations/flink/flink.yaml, unchanged except for the JobManager address, governing a REAL
 * Apache Flink 1.20 cluster: Aegis lists the real running job over Flink's REST API, blocks an
 * unauthorized cancel, and after validate-approve-promote the real job reaches CANCELED.
 */
class RealFlinkTest {

    @TempDir
    Path dir;

    /** Endless source so the job stays RUNNING until cancelled. */
    static final class Endless implements SourceFunction<Long> {
        private static final long serialVersionUID = 1L;
        private volatile boolean running = true;

        @Override
        public void run(SourceContext<Long> ctx) throws Exception {
            long i = 0;
            while (running) {
                synchronized (ctx.getCheckpointLock()) {
                    ctx.collect(i++);
                }
                Thread.sleep(50);
            }
        }

        @Override
        public void cancel() {
            running = false;
        }
    }

    @Test
    void shippedFlinkConfigGovernsARealFlinkCluster() throws Exception {
        Configuration conf = new Configuration();
        conf.set(RestOptions.BIND_PORT, "0");
        conf.set(RestOptions.ADDRESS, "127.0.0.1");
        try (MiniCluster flink = new MiniCluster(new MiniClusterConfiguration.Builder()
                .setConfiguration(conf).setNumTaskManagers(1).setNumSlotsPerTaskManager(1).build())) {
            flink.start();
            URI rest = flink.getRestAddress().get();

            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            env.addSource(new Endless()).name("endless").addSink(new DiscardingSink<>());
            JobGraph graph = env.getStreamGraph().getJobGraph();
            JobID jobId = flink.submitJob(graph).get().getJobID();
            waitFor(flink, jobId, JobStatus.RUNNING);

            String yaml = Files.readString(Path.of("..", "..", "adopters", "configurations", "flink", "flink.yaml"))
                    .replace("http://flink-jobmanager:8081", "http://127.0.0.1:" + rest.getPort())
                    .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]");
            Path cfg = dir.resolve("flink.yaml");
            Files.writeString(cfg, yaml);
            Map<String, String> envVars = Map.of("AEGIS_SIGNING_KEY", "real-flink-validation-signing-key-0123456789");
            try (Gateway g = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(envVars::get))) {
                CallerIdentity user = CallerIdentity.of("ana", Set.of("flink-users"), "idp", "jwt");
                CallerIdentity op = CallerIdentity.of("oscar", Set.of("flink-operators"), "idp", "jwt");
                CallerIdentity approver = CallerIdentity.of("petra", Set.of("flink-approvers"), "idp", "jwt");
                String jid = jobId.toHexString();

                CallOutcome list = g.engine().call(user, new ToolCall("1", "flink_list_jobs", Map.of(), Map.of(), ""));
                String listed = Json.write(list.toMcpResult());
                assertTrue(list.allowed() && listed.contains(jid) && listed.contains("RUNNING"), listed);

                CallOutcome detail = g.engine().call(user, new ToolCall("2", "flink_get_job", Map.of("jobId", jid), Map.of(), ""));
                assertTrue(detail.allowed() && Json.write(detail.toMcpResult()).contains("endless"), Json.write(detail.toMcpResult()));

                assertEquals(DenyCodes.READONLY_CALLER, g.engine().call(user,
                        new ToolCall("3", "flink_cancel_job", Map.of("jobId", jid), Map.of(), "")).decision().code());
                CallOutcome noApproval = g.engine().call(op, new ToolCall("4", "flink_cancel_job", Map.of("jobId", jid), Map.of(), ""));
                assertEquals(DenyCodes.APPROVAL_REQUIRED, noApproval.decision().code());
                assertEquals(JobStatus.RUNNING, flink.getJobStatus(jobId).get(), "denied calls never touched the real job");

                CallOutcome dry = g.engine().call(op, new ToolCall("5", "flink_cancel_job", Map.of("jobId", jid),
                        Map.of(ControlTokens.META_DRY_RUN, true), ""));
                String rid = (String) dry.governance().get("approvalRequestId");
                String receipt = (String) dry.governance().get("dryRunReceipt");
                assertNotNull(receipt);
                g.engine().approvals().decide(rid, approver, true);
                String token = (String) g.engine().approvals().get(rid, op).get("token");
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put(ControlTokens.META_APPROVAL, token);
                meta.put(ControlTokens.META_RECEIPT, receipt);
                CallOutcome cancel = g.engine().call(op, new ToolCall("6", "flink_cancel_job", Map.of("jobId", jid), meta, ""));
                assertTrue(cancel.allowed(), Json.write(cancel.toMcpResult()));
                waitFor(flink, jobId, JobStatus.CANCELED);
            }
        }
    }

    private static void waitFor(MiniCluster flink, JobID id, JobStatus status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (flink.getJobStatus(id).get() == status) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job did not reach " + status + " (now " + flink.getJobStatus(id).get() + ")");
    }
}
