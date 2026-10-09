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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.catalog.SignedServerCatalog;
import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import io.github.vaquarkhan.aegis.validation.frontdoor.SignedServers;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/flink: the canonical {@code adopters/configurations/flink/flink.yaml} governing a REAL
 * Apache Flink 1.20.1 cluster, through both front doors. Without Knox, Aegis's own server authenticates
 * static bearer tokens. With Knox, a stock Knox 3.0.0 gateway authenticates the same people against its
 * demo LDAP and admits the route; Aegis governs the tool call in the body. The story and the real
 * effect (a RUNNING job reaching CANCELED only after an independent approval) are the same.
 */
class FlinkFrontDoorsTest {

    private static MiniCluster flink;
    private static URI rest;

    @TempDir
    Path dir;

    @BeforeAll
    static void startFlink() throws Exception {
        Configuration conf = new Configuration();
        conf.set(RestOptions.BIND_PORT, "0");
        conf.set(RestOptions.ADDRESS, "127.0.0.1");
        flink = new MiniCluster(new MiniClusterConfiguration.Builder()
                .setConfiguration(conf).setNumTaskManagers(1).setNumSlotsPerTaskManager(2).build());
        flink.start();
        rest = flink.getRestAddress().get();
        io.github.vaquarkhan.aegis.validation.frontdoor.RunRecord.engineInProcess(
                "Apache Flink 1.20.1 runs inside the test JVM as a `MiniCluster` (1 TaskManager, 2 slots) with its "
                        + "JobManager REST endpoint on `127.0.0.1:" + rest.getPort() + "`: the same REST API a standalone "
                        + "cluster serves. To use your own cluster instead, start it with `bin/start-cluster.sh` and put its "
                        + "REST address in the YAML. Per front door, the test submits an endless streaming job "
                        + "(source to discarding sink) with the Flink client, which `flink_cancel_job` then cancels.");
    }

    @AfterAll
    static void stopFlink() throws Exception {
        if (flink != null) {
            flink.close();
        }
    }

    @ParameterizedTest(name = "flink through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealFlinkCluster(String door) throws Exception {
        AdopterScenario scenario = AdopterScenario.of("flink", AdopterScenario.canonical("flink", "flink.yaml"),
                yaml -> yaml.replace("http://flink-jobmanager:8081", "http://127.0.0.1:" + rest.getPort())
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("flink-users"),
                        Persona.OPERATOR, List.of("flink-operators"),
                        Persona.APPROVER, List.of("flink-approvers")),
                Map.of());
        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            JobID job = submitEndlessJob();
            String jid = job.toHexString();
            d.note("submitted real Flink job " + jid + "; JobManager REST " + rest);

            GovernedFlow.read(d, "flink_list_jobs", Map.of(), jid);
            GovernedFlow.destructive(d, "flink_cancel_job", Map.of("jobId", jid),
                    () -> assertEquals(JobStatus.RUNNING, flink.getJobStatus(job).get(), "denied calls never touched the job"),
                    () -> {
                        waitFor(job, JobStatus.CANCELED);
                        d.note("Flink reports job " + jid + " " + flink.getJobStatus(job).get());
                    });
        }
    }

    private static AdopterScenario scenario() throws Exception {
        return AdopterScenario.of("flink", AdopterScenario.canonical("flink", "flink.yaml"),
                yaml -> yaml.replace("http://flink-jobmanager:8081", "http://127.0.0.1:" + rest.getPort())
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("flink-users"),
                        Persona.OPERATOR, List.of("flink-operators"),
                        Persona.APPROVER, List.of("flink-approvers")),
                Map.of());
    }

    /**
     * The same real cluster with a signed server catalog: the door lists exactly the reviewed Flink tools and
     * governs a real read; a break-glass order that contains the only server leaves nothing to start, so the
     * gateway refuses to start rather than serve an empty or unreviewed catalog.
     */
    @ParameterizedTest(name = "flink signed server catalog through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void aSignedServerCatalogGovernsTheRealClusterAndBreakGlassContainsIt(String door) throws Exception {
        SignedServers.Signed signed = SignedServers.sign(scenario(), "flink-server-catalog", dir, Map.of());
        try (FrontDoor d = FrontDoor.open(door, signed.scenario(), dir.resolve("signed"))) {
            JobID job = submitEndlessJob();
            d.note("server catalog signed for " + signed.pins().size() + " Flink tools; submitted real job " + job.toHexString());
            SignedServers.assertOnlySignedToolsAreActive(d, signed);
            GovernedFlow.read(d, "flink_list_jobs", Map.of(), job.toHexString());
            flink.cancelJob(job).get();
        }

        SignedServers.Signed contained = SignedServers.sign(scenario(), "flink-break-glass", dir,
                Map.of("flink", SignedServerCatalog.State.QUARANTINED));
        Exception refused = assertThrows(Exception.class,
                () -> FrontDoor.open(door, contained.scenario(), dir.resolve("contained")).close());
        String why = SignedServers.messages(refused);
        assertTrue(why.contains(FrontDoor.OWN_HOST.equals(door) ? "no source that the signed server catalog lets start"
                : "Aegis failed to load inside Knox"), why);
    }

    /** The signature binds the reviewed configuration: pointing the source elsewhere stops the gateway. */
    @org.junit.jupiter.api.Test
    void editingTheReviewedFlinkSourceStopsTheGateway() throws Exception {
        SignedServers.Signed signed = SignedServers.sign(scenario(), "flink-server-catalog-edited", dir, Map.of());
        AdopterScenario s = signed.scenario();
        AdopterScenario moved = new AdopterScenario(s.adopter(),
                s.configYaml().replace("http://127.0.0.1:" + rest.getPort(), "http://127.0.0.2:" + rest.getPort()),
                s.groups(), s.env(), s.knoxPlugins(), s.knoxJvmArgs(), s.baseDirFiles());
        Exception refused = assertThrows(Exception.class, () -> FrontDoor.open(FrontDoor.OWN_HOST, moved, dir.resolve("edited")).close());
        String why = SignedServers.messages(refused);
        assertTrue(why.contains("differs from the configuration reviewed in the signed server catalog"), why);
    }

    private static JobID submitEndlessJob() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.addSource(new RealFlinkTest.Endless()).name("endless").addSink(new DiscardingSink<>());
        JobID id = flink.submitJob(env.getStreamGraph().getJobGraph()).get().getJobID();
        waitFor(id, JobStatus.RUNNING);
        return id;
    }

    private static void waitFor(JobID id, JobStatus status) throws Exception {
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
