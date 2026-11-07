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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/ignite: the canonical {@code adopters/configurations/adapters/ignite/ignite.yaml} against a
 * real Apache Ignite 2.18.0 server node with the optional {@code ignite-rest-http} module (the REST v1
 * {@code /ignite} endpoint), started by Ignite's own {@code CommandLineStartup} from a Spring XML file as
 * {@code bin/ignite.sh} does, in its own JVM, through both front doors.
 */
class IgniteFrontDoorsTest {

    /** The JVM options bin/include/jvmdefaults.sh in Ignite 2.18.0 sets for Java 15 and later. */
    private static final List<String> JAVA17_OPTS = List.of(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
            "--add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED",
            "--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED",
            "--add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED",
            "--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED", "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", "--add-opens=java.base/java.math=ALL-UNNAMED",
            "--add-opens=java.sql/java.sql=ALL-UNNAMED", "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.time=ALL-UNNAMED", "--add-opens=java.base/java.text=ALL-UNNAMED",
            "--add-opens=java.management/sun.management=ALL-UNNAMED", "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static JavaServer node;
    private static int restPort;

    @TempDir
    Path dir;

    @BeforeAll
    static void startIgnite() throws Exception {
        Path work = Path.of("target", "ignite-server").toAbsolutePath();
        Files.createDirectories(work);
        restPort = JavaServer.freePort();
        int discoveryPort = JavaServer.freePort();
        int commPort = JavaServer.freePort();
        Path xml = work.resolve("aegis-ignite.xml");
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="http://www.springframework.org/schema/beans
                                           http://www.springframework.org/schema/beans/spring-beans.xsd">
                  <bean class="org.apache.ignite.configuration.IgniteConfiguration">
                    <property name="igniteInstanceName" value="aegis-validation"/>
                    <property name="localHost" value="127.0.0.1"/>
                    <property name="workDirectory" value="%s"/>
                    <property name="discoverySpi">
                      <bean class="org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi">
                        <property name="localPort" value="%d"/>
                        <property name="localPortRange" value="0"/>
                        <property name="ipFinder">
                          <bean class="org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder">
                            <property name="addresses"><list><value>127.0.0.1:%d</value></list></property>
                          </bean>
                        </property>
                      </bean>
                    </property>
                    <property name="communicationSpi">
                      <bean class="org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi">
                        <property name="localPort" value="%d"/>
                        <property name="localPortRange" value="0"/>
                      </bean>
                    </property>
                  </bean>
                </beans>
                """.formatted(work.resolve("work").toString().replace('\\', '/'), discoveryPort, discoveryPort, commPort));
        List<String> jvm = new ArrayList<>(JAVA17_OPTS);
        jvm.addAll(List.of("-Xms256m", "-Xmx1g",
                "-DIGNITE_HOME=" + work, "-DIGNITE_JETTY_HOST=127.0.0.1", "-DIGNITE_JETTY_PORT=" + restPort,
                // No call home from a test run.
                "-DIGNITE_UPDATE_NOTIFIER=false", "-DIGNITE_NO_ASCII=true"));
        node = new JavaServer("aegis.ignite.serverClasspathFile", jvm,
                "org.apache.ignite.startup.cmdline.CommandLineStartup", List.of(xml.toString()), Map.of(),
                work.resolve("ignite-node.log"));
        node.awaitReady(() -> rest("cmd=version").contains("\"successStatus\":0"), Duration.ofSeconds(180), "Ignite REST");
        for (String cache : List.of("orders", "scratch_ownhost", "scratch_knox")) {
            ok(rest("cmd=getorcreate&cacheName=" + cache));
            ok(rest("cmd=put&cacheName=" + cache + "&key=k1&val=v1"));
        }
    }

    @AfterAll
    static void stopIgnite() throws Exception {
        if (node != null) {
            node.close();
        }
    }

    @ParameterizedTest(name = "ignite through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealIgniteNode(String door) throws Exception {
        String scratch = "scratch_" + door.replace("-", "");
        assertTrue(caches().contains("\"" + scratch + "\""), "node starts with cache " + scratch);

        AdopterScenario scenario = AdopterScenario.of("ignite", AdopterScenario.canonical("adapters", "ignite", "ignite.yaml"),
                yaml -> yaml.replace("http://ignite:8080", "http://127.0.0.1:" + restPort)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("ignite-users"),
                        Persona.OPERATOR, List.of("ignite-operators"),
                        Persona.APPROVER, List.of("ignite-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real Ignite 2.18.0 node, REST on 127.0.0.1:" + restPort + "; caches orders and " + scratch);
            GovernedFlow.read(d, "ignite_get_version", Map.of(), "2.18.0");
            GovernedFlow.read(d, "ignite_list_caches", Map.of(), "orders");
            GovernedFlow.destructive(d, "ignite_destroy_cache", Map.of("cache", scratch),
                    () -> assertTrue(caches().contains("\"" + scratch + "\""), "denied and previewed calls kept " + scratch),
                    () -> {
                        String now = caches();
                        assertFalse(now.contains("\"" + scratch + "\""), scratch + " is gone: " + now);
                        assertTrue(now.contains("\"orders\""), "orders untouched: " + now);
                        d.note("the node no longer has cache " + scratch + " and still has orders");
                    });
        }
    }

    /** The cache names in the node's topology, read from the REST API directly, outside Aegis. */
    private static String caches() throws Exception {
        String top = rest("cmd=top");
        ok(top);
        return top;
    }

    private static String rest(String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + restPort + "/ignite?" + query))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void ok(String reply) {
        if (!reply.contains("\"successStatus\":0")) {
            throw new IllegalStateException("Ignite REST refused: " + reply);
        }
    }
}
