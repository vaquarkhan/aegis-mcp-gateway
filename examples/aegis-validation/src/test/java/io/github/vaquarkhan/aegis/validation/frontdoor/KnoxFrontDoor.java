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
package io.github.vaquarkhan.aegis.validation.frontdoor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * With Knox: a real Apache Knox 3.0.0 gateway in front. Knox authenticates the persona against its demo
 * LDAP (ShiroProvider, HTTP Basic), asserts groups ({@code group.principal.mapping}), and gates the
 * route with {@code AclsAuthz}; the {@code AEGIS_MCP} service then hands the request to Aegis. Knox
 * decides who may reach {@code /mcp}; Aegis decides which tool, resource and arguments they may use.
 */
final class KnoxFrontDoor extends FrontDoor {

    private final KnoxRuntime knox;
    private final String topologyName;
    private final Path config;

    KnoxFrontDoor(AdopterScenario scenario, Path workDir, Path home) throws Exception {
        this(scenario, workDir, home, prepare(scenario, workDir));
    }

    private KnoxFrontDoor(AdopterScenario scenario, Path workDir, Path home, Path config) throws Exception {
        this(scenario, workDir, config, start(scenario, workDir, home, config));
    }

    private KnoxFrontDoor(AdopterScenario scenario, Path workDir, Path config, KnoxRuntime knox) throws Exception {
        super(scenario, workDir, HttpClient.newBuilder().sslContext(knox.tls()).build());
        this.knox = knox;
        this.config = config;
        this.topologyName = topologyName(scenario);
        // Knox deploys the topology at startup; wait until the Aegis route answers through the provider chain.
        HttpClient probe = HttpClient.newBuilder().sslContext(knox.tls()).build();
        try {
            KnoxRuntime.waitUntil(() -> {
                try {
                    HttpResponse<String> r = probe.send(HttpRequest.newBuilder(base().resolve("health"))
                            .header("Authorization", authorization(Persona.READER)).timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() == 503) {
                        throw new IllegalStateException("Aegis failed to load inside Knox: " + r.body());
                    }
                    return r.statusCode() == 200;
                } catch (java.io.IOException e) {
                    return false;
                }
            }, Duration.ofSeconds(180), "topology " + topologyName + " to deploy");
        } catch (Exception e) {
            try {
                knox.close();
            } catch (Exception close) {
                e.addSuppressed(close);
            }
            throw e;
        }
    }

    private static Path prepare(AdopterScenario scenario, Path workDir) throws Exception {
        Path dir = Files.createDirectories(workDir.resolve(KNOX));
        Path config = dir.resolve(scenario.adopter() + ".yaml");
        Files.writeString(config, scenario.configYaml());
        return config;
    }

    private static KnoxRuntime start(AdopterScenario scenario, Path workDir, Path home, Path config) throws Exception {
        Path logs = Path.of(System.getProperty("aegis.results.dir", "target/adopter-results"), scenario.adopter(), "knox-logs");
        // The exact Aegis file and topology this door used, kept with the example. The topology's aegis.config
        // pointed at the run's temporary copy; the kept topology points where an operator would put the file.
        RunRecord.write(scenario.adopter(), "aegis/knox-aegis.yaml", scenario.configYaml());
        String kept = topology(scenario, config).replace(config.toAbsolutePath().toString().replace('\\', '/'),
                "${KNOX_HOME}/conf/aegis/" + scenario.adopter() + ".yaml");
        int decl = kept.indexOf("?>\n") + 3;
        RunRecord.write(scenario.adopter(), "aegis/knox-topology.xml", kept.substring(0, decl) + RunRecord.HEADER_XML
                + "<!-- Kept from the run. Replace ${KNOX_HOME} with your Knox directory: Knox does not expand it. -->\n"
                + kept.substring(decl));
        return new KnoxRuntime(home, topologyName(scenario), topology(scenario, config), scenario.env(), logs,
                scenario.knoxPlugins(), scenario.knoxJvmArgs());
    }

    @Override
    protected String startInstructions() {
        String a = scenario.adopter();
        StringBuilder md = new StringBuilder();
        md.append("The files: [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml), the same adopter profile with no `host:` ")
                .append("section (Knox is the host), and [`aegis/knox-topology.xml`](aegis/knox-topology.xml), the Knox topology: ")
                .append("ShiroProvider against Knox's demo LDAP, Default identity assertion with `group.principal.mapping` ")
                .append("(person to adopter groups), `AclsAuthz` on the route, and the `AEGIS_MCP` service.\n\n")
                .append("Steps, in the order the test performed them on a stock Knox 3.0.0 (`$AEGIS_KNOX_HOME`, from ")
                .append("`examples/adopters/knox/install-knox.ps1` or `.sh`):\n\n")
                .append("```bash\ncd $AEGIS_KNOX_HOME\n")
                .append("# 1. the Aegis extension\n")
                .append("cp $REPO/aegis-knox3-extension/target/aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar ext/\n");
        if (!scenario.knoxPlugins().isEmpty()) {
            md.append("# 1b. the source plugin: copy each jar marked ADD in results/knox-ext-plugins.log to ext/aegis-plugin-<jar>\n");
        }
        md.append("# 2. a master secret and a TLS certificate for localhost\n")
                .append("java -jar bin/knoxcli.jar create-master --master <a master secret of your choice>\n")
                .append("java -jar bin/knoxcli.jar create-cert --hostname localhost\n")
                .append("# 3. conf/gateway-site.xml: set gateway.port (the test used a free port) and gateway.host 127.0.0.1\n")
                .append("# 4. the Aegis configuration and the topology\n")
                .append("mkdir -p conf/aegis && cp $REPO/examples/adopters/").append(a).append("/aegis/knox-aegis.yaml conf/aegis/")
                .append(a).append(".yaml\n")
                .append("cp $REPO/examples/adopters/").append(a).append("/aegis/knox-topology.xml conf/topologies/aegis-").append(a)
                .append(".xml   # then replace ${KNOX_HOME} in it\n")
                .append("# 5. Knox's demo LDAP (port ").append(KnoxRuntime.LDAP_PORT).append(") and the gateway\n")
                .append("java -jar bin/ldap.jar conf &\n");
        String jvm = String.join(" ", scenario.knoxJvmArgs());
        md.append("java ").append(jvm.isEmpty() ? "" : RunRecord.portable(jvm) + " ").append("-jar bin/gateway.jar\n```\n\n");
        Map<String, String> env = scenario.env();
        md.append("Environment for the gateway process (the Aegis configuration reads it):\n\n").append(RunRecord.envList(env)).append('\n')
                .append("The route: `https://localhost:<gateway.port>/gateway/aegis-").append(a).append("/aegis-mcp/v1/mcp`; the admin ")
                .append("API is `.../aegis-mcp/v1/admin/`. Knox demo LDAP users are in `conf/users.ldif`; supply that installation's ")
                .append("demo credentials through your terminal or IDE, never in a committed configuration.\n");
        return md.toString();
    }

    @Override
    protected String curlFirstCall(String tool, String jsonBody) {
        return "# -k because the certificate is the self-signed one from step 2; or export it with knoxcli export-cert\n"
                + "curl -sk https://localhost:<gateway.port>/gateway/aegis-" + scenario.adopter() + "/aegis-mcp/v1/mcp \\\n"
                + "  -u <knox-demo-user>:<knox-demo-password> \\\n"
                + "  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \\\n"
                + "  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: " + tool + "' \\\n"
                + "  -d '" + jsonBody.replace("'", "'\\''") + "'";
    }

    private static String topologyName(AdopterScenario scenario) {
        return "aegis-" + scenario.adopter();
    }

    /** The topology an operator would write: demo LDAP, Default identity assertion, AclsAuthz, AEGIS_MCP. */
    static String topology(AdopterScenario scenario, Path config) {
        List<String> mapping = new ArrayList<>();
        for (Persona p : Persona.values()) {
            if (!scenario.groupsOf(p).isEmpty()) {
                mapping.add(p.user() + "=" + String.join(",", scenario.groupsOf(p)));
            }
        }
        String acl = Persona.READER.user() + "," + Persona.OPERATOR.user() + "," + Persona.APPROVER.user() + ";*;*";
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <topology>
                    <gateway>
                        <provider>
                            <role>authentication</role>
                            <name>ShiroProvider</name>
                            <enabled>true</enabled>
                            <param><name>sessionTimeout</name><value>30</value></param>
                            <param><name>main.ldapRealm</name><value>org.apache.knox.gateway.shirorealm.KnoxLdapRealm</value></param>
                            <param><name>main.ldapContextFactory</name><value>org.apache.knox.gateway.shirorealm.KnoxLdapContextFactory</value></param>
                            <param><name>main.ldapRealm.contextFactory</name><value>$ldapContextFactory</value></param>
                            <param><name>main.ldapRealm.userDnTemplate</name><value>uid={0},ou=people,dc=hadoop,dc=apache,dc=org</value></param>
                            <param><name>main.ldapRealm.contextFactory.url</name><value>ldap://localhost:%d</value></param>
                            <param><name>main.ldapRealm.contextFactory.authenticationMechanism</name><value>simple</value></param>
                            <param><name>urls./**</name><value>authcBasic</value></param>
                        </provider>
                        <provider>
                            <role>identity-assertion</role>
                            <name>Default</name>
                            <enabled>true</enabled>
                            <param><name>group.principal.mapping</name><value>%s</value></param>
                        </provider>
                        <provider>
                            <role>authorization</role>
                            <name>AclsAuthz</name>
                            <enabled>true</enabled>
                            <param><name>AEGIS_MCP.acl</name><value>%s</value></param>
                        </provider>
                    </gateway>
                    <service>
                        <role>AEGIS_MCP</role>
                        <param><name>aegis.config</name><value>%s</value></param>
                    </service>
                </topology>
                """.formatted(KnoxRuntime.LDAP_PORT, String.join(";", mapping), acl,
                config.toAbsolutePath().toString().replace('\\', '/'));
    }

    private URI base() {
        return URI.create("https://localhost:" + knox.port() + "/gateway/" + topologyName + "/aegis-mcp/v1/");
    }

    @Override
    public String name() {
        return KNOX;
    }

    @Override
    protected URI mcpUri() {
        return base().resolve("mcp");
    }

    @Override
    protected URI adminUri(String path) {
        return base().resolve("admin/" + path);
    }

    @Override
    protected String authorization(Persona who) {
        return "Basic " + Base64.getEncoder().encodeToString((who.user() + ":" + who.password()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Path auditJournal() {
        return config.getParent().resolve(AuditPath.of(scenario.configYaml()));
    }

    @Override
    public void close() throws Exception {
        try {
            knox.close();
        } finally {
            keepAudit();
            Path log = knox.gatewayLog();
            if (Files.isRegularFile(log)) {
                Path dest = Path.of(System.getProperty("aegis.results.dir", "target/adopter-results"), scenario.adopter(),
                        "knox-logs", "gateway.log");
                Files.copy(log, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
