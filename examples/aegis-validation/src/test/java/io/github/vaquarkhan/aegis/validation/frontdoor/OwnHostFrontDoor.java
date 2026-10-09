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

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.gateway.AegisServer;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Without Knox: the Aegis own-host server ({@code aegis-gateway}) on loopback HTTP, with static bearer
 * tokens for the four personas. Same adopter YAML; only a {@code host:} section is appended.
 */
final class OwnHostFrontDoor extends FrontDoor {

    private final Gateway gateway;
    private final AegisServer server;
    private final Path config;
    private final Map<String, String> env;

    OwnHostFrontDoor(AdopterScenario scenario, Path workDir) throws Exception {
        super(scenario, workDir, HttpClient.newHttpClient());
        Path dir = Files.createDirectories(workDir.resolve(OWN_HOST));
        Map<String, String> env = new LinkedHashMap<>(scenario.env());
        StringBuilder host = new StringBuilder("""

                # Appended for the own-host front door: loopback listener, static tokens per persona.
                host:
                  listen: {host: 127.0.0.1, port: 0}
                  identity:
                    type: static
                    static:
                      tokens:
                """);
        for (Persona p : Persona.values()) {
            env.put(p.tokenEnv(), p.ownHostToken());
            host.append("        - {tokenEnv: ").append(p.tokenEnv()).append(", actor: ").append(p.user())
                    .append(", groups: [").append(String.join(", ", scenario.groupsOf(p))).append("]}\n");
        }
        config = dir.resolve(scenario.adopter() + ".yaml");
        Files.writeString(config, scenario.configYaml() + host);
        scenario.installBaseDirFiles(dir);
        // The exact file this door loaded, kept with the example.
        RunRecord.write(scenario.adopter(), "aegis/own-host.yaml", scenario.configYaml() + host);
        this.env = env;
        gateway = GatewayFactory.build(config, BuildContext.defaults(dir).withEnv(env::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gateway.hostSection("host"), dir, env::get, Clock.systemUTC());
        server = new AegisServer(gateway, st);
        server.start();
    }

    @Override
    public String name() {
        return OWN_HOST;
    }

    @Override
    protected URI mcpUri() {
        return URI.create("http://127.0.0.1:" + server.port() + "/mcp");
    }

    @Override
    protected URI adminUri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + "/aegis/v1/" + path);
    }

    @Override
    protected String authorization(Persona who) {
        return "Bearer " + who.ownHostToken();
    }

    @Override
    public Path auditJournal() {
        return config.getParent().resolve(AuditPath.of(scenario.configYaml()));
    }

    @Override
    protected String startInstructions() {
        String a = scenario.adopter();
        StringBuilder md = new StringBuilder();
        md.append("The file: [`aegis/own-host.yaml`](aegis/own-host.yaml), exactly what this door loaded: the shipped adopter ")
                .append("profile with the engine address and egress network substituted, plus a `host:` section (loopback ")
                .append("listener on a free port, one static bearer token per person, read from the environment).\n\n")
                .append("Environment the gateway reads:\n\n").append(RunRecord.envList(env)).append('\n')
                .append("Start it from the repository root after `mvn -B -Preal-engines -DskipTests install`:\n\n```bash\n");
        if (scenario.knoxPlugins().isEmpty()) {
            md.append("java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \\\n  --config examples/adopters/")
                    .append(a).append("/aegis/own-host.yaml\n");
        } else {
            md.append("# this profile uses a source plugin: put it and its jars on the classpath (same jars as Knox ext/, below)\n")
                    .append("java -cp \"aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar")
                    .append(java.io.File.pathSeparator).append("<plugin jars>\" io.github.vaquarkhan.aegis.gateway.Main \\\n")
                    .append("  --config examples/adopters/").append(a).append("/aegis/own-host.yaml\n");
            md.append("```\n\nPlugin jars this run used:\n\n```text\n");
            for (Path p : scenario.knoxPlugins()) {
                md.append(RunRecord.portable(p.toString())).append('\n');
            }
        }
        md.append("```\n\nThe gateway logs `Aegis gateway listening on http://127.0.0.1:<port>/mcp`; `port: 0` in the file means ")
                .append("any free port. The admin API is `http://127.0.0.1:<port>/aegis/v1/`. The engine address in the file ")
                .append("is the port the test picked for its engine; change it to yours.\n");
        return md.toString();
    }

    @Override
    protected String curlFirstCall(String tool, String jsonBody) {
        return "curl -s http://127.0.0.1:<port>/mcp \\\n"
                + "  -H \"Authorization: Bearer $" + Persona.READER.tokenEnv() + "\" \\\n"
                + "  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \\\n"
                + "  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: " + tool + "' \\\n"
                + "  -d '" + jsonBody.replace("'", "'\\''") + "'";
    }

    @Override
    public void close() throws Exception {
        try {
            server.close();
        } finally {
            gateway.close();
            keepAudit();
        }
    }
}
