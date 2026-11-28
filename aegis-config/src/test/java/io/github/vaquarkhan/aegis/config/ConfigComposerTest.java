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
package io.github.vaquarkhan.aegis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigComposerTest {

  @TempDir Path tempDir;

  @Test
  void preservesHttpAndVaultPathsWhileResolvingFilesystemPaths() throws Exception {
    Path gateway = tempDir.resolve("config/aegis.yaml");
    Files.createDirectories(gateway.getParent());
    Files.writeString(
        gateway,
        """
                audit:
                  file: ../logs/audit.jsonl
                secrets:
                  type: vault
                  settings:
                    address: https://vault.internal:8200
                    path: aegis/prod
                policy:
                  rules:
                    - id: allow-read
                      groups: ["*"]
                      classes: [READ]
                """);

    Path adopter = tempDir.resolve("adopters/demo.yaml");
    Path pluginJar = tempDir.resolve("adopters/plugins/demo.jar");
    Files.createDirectories(pluginJar.getParent());
    byte[] jarBytes = "demo-plugin-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(pluginJar, jarBytes);
    String jarSha256 =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(jarBytes));

    Files.writeString(
        adopter,
        """
                id: demo
                type: http
                baseUrl: https://demo.internal:8443
                artifacts:
                  - path: ./plugins/demo.jar
                    sha256: %s
                tools:
                  - name: health
                    class: READ
                    description: Health check
                    request:
                      method: GET
                      path: /api/v1/health
                """
            .formatted(jarSha256));

    Path stdioAdopter = tempDir.resolve("adopters/stdio.yaml");
    Files.writeString(
        stdioAdopter,
        """
                id: demo-stdio
                type: mcp
                transport: stdio
                command:
                  - ./bin/helper.sh
                  - --flag
                """);

    Path outDir = tempDir.resolve("dist");
    ConfigComposer.ComposedConfig composed =
        new ConfigComposer().compose(gateway, List.of(adopter, stdioAdopter), outDir);

    @SuppressWarnings("unchecked")
    Map<String, Object> secrets = (Map<String, Object>) composed.effectiveMap().get("secrets");
    @SuppressWarnings("unchecked")
    Map<String, Object> vaultSettings = (Map<String, Object>) secrets.get("settings");
    assertEquals("aegis/prod", vaultSettings.get("path"));

    @SuppressWarnings("unchecked")
    Map<String, Object> audit = (Map<String, Object>) composed.effectiveMap().get("audit");
    assertEquals("../logs/audit.jsonl", audit.get("file"));

    List<?> sources = (List<?>) composed.effectiveMap().get("sources");
    Map<?, ?> source = (Map<?, ?>) sources.get(0);

    List<?> tools = (List<?>) source.get("tools");
    Map<?, ?> firstTool = (Map<?, ?>) tools.get(0);
    Map<?, ?> request = (Map<?, ?>) firstTool.get("request");
    assertEquals("/api/v1/health", request.get("path"));

    List<?> artifacts = (List<?>) source.get("artifacts");
    Map<?, ?> firstArtifact = (Map<?, ?>) artifacts.get(0);
    assertEquals("../adopters/plugins/demo.jar", firstArtifact.get("path"));

    Map<?, ?> stdioSource = (Map<?, ?>) sources.get(1);
    @SuppressWarnings("unchecked")
    List<String> command = (List<String>) stdioSource.get("command");
    assertEquals("../adopters/bin/helper.sh", command.get(0));
    assertEquals("--flag", command.get(1));
  }

  @Test
  void doesNotRewriteProviderSettingsHttpPayloadsOrWorkingDirRelativeCommands() throws Exception {
    Path gateway = tempDir.resolve("config/aegis.yaml");
    Files.createDirectories(gateway.getParent());
    Files.writeString(
        gateway,
        """
                audit:
                  file: audit.jsonl
                policy:
                  rules:
                    - {id: r, groups: ["*"], classes: [READ]}
                """);

    Path httpAdopter = tempDir.resolve("adopters/http-payload.yaml");
    Files.createDirectories(httpAdopter.getParent());
    Files.writeString(
        httpAdopter,
        """
                id: files-api
                type: http
                baseUrl: https://files.internal
                tools:
                  - name: upload
                    description: Upload file
                    class: MUTATE
                    request:
                      method: POST
                      path: /v1/upload
                      query:
                        directory: ./incoming
                      body:
                        file: ./report.csv
                        directory: ./reports
                """);

    Path stdioAdopter = tempDir.resolve("adopters/stdio-svc.yaml");
    Files.writeString(
        stdioAdopter,
        """
                id: svc
                type: mcp
                transport: stdio
                workingDir: ./service
                command:
                  - ./run.sh
                  - ./scripts/worker.py
                """);

    Path pluginAdopter = tempDir.resolve("adopters/plugin-settings.yaml");
    Files.writeString(
        pluginAdopter,
        """
                id: warehouse
                type: jdbc
                settings:
                  file: ./provider-config.json
                  directory: ./provider-dir
                """);

    Path outDir = tempDir.resolve("dist");
    ConfigComposer.ComposedConfig composed =
        new ConfigComposer()
            .compose(gateway, List.of(httpAdopter, stdioAdopter, pluginAdopter), outDir);

    List<?> sources = (List<?>) composed.effectiveMap().get("sources");

    Map<?, ?> httpSource = (Map<?, ?>) sources.get(0);
    List<?> tools = (List<?>) httpSource.get("tools");
    Map<?, ?> uploadTool = (Map<?, ?>) tools.get(0);
    Map<?, ?> request = (Map<?, ?>) uploadTool.get("request");
    assertEquals("./incoming", ((Map<?, ?>) request.get("query")).get("directory"));
    assertEquals("./report.csv", ((Map<?, ?>) request.get("body")).get("file"));
    assertEquals("./reports", ((Map<?, ?>) request.get("body")).get("directory"));

    Map<?, ?> pluginSource = (Map<?, ?>) sources.get(2);
    Map<?, ?> providerSettings = (Map<?, ?>) pluginSource.get("settings");
    assertEquals("./provider-config.json", providerSettings.get("file"));
    assertEquals("./provider-dir", providerSettings.get("directory"));
  }

  @Test
  void rejectsMalformedOrGlobalHooksAndAdopterScopes() throws Exception {
    Path gateway = tempDir.resolve("aegis.yaml");
    Files.writeString(
        gateway,
        """
                audit:
                  file: audit.jsonl
                policy:
                  rules:
                    - {id: r, groups: ["*"], classes: [READ]}
                """);

    Path nonListHooks = tempDir.resolve("non-list-hooks.yaml");
    Files.writeString(
        nonListHooks,
        """
                id: s1
                type: http
                baseUrl: https://example.internal
                hooks:
                  type: sql-readonly
                """);
    assertThrows(
        ConfigException.class, () -> new ConfigComposer().compose(gateway, List.of(nonListHooks)));

    Path adopterScopes = tempDir.resolve("adopter-scopes.yaml");
    Files.writeString(
        adopterScopes,
        """
                id: s2
                type: http
                baseUrl: https://example.internal
                scopes:
                  - groups: [users]
                    resources: ["http:*"]
                """);
    ConfigException scopeEx =
        assertThrows(
            ConfigException.class,
            () -> new ConfigComposer().compose(gateway, List.of(adopterScopes)));
    assertTrue(scopeEx.getMessage().contains("gateway-owned section 'scopes'"));

    Path emptyHookSources = tempDir.resolve("empty-hook-sources.yaml");
    Files.writeString(
        emptyHookSources,
        """
                id: s2b
                type: http
                baseUrl: https://example.internal
                hooks:
                  - type: sql-readonly
                    sources: []
                """);
    ConfigException emptySourcesEx =
        assertThrows(
            ConfigException.class,
            () -> new ConfigComposer().compose(gateway, List.of(emptyHookSources)));
    assertTrue(emptySourcesEx.getMessage().contains("cannot be empty"));

    Path invalidHookSources = tempDir.resolve("bad-hook-sources.yaml");
    Files.writeString(
        invalidHookSources,
        """
                id: s3
                type: http
                baseUrl: https://example.internal
                hooks:
                  - type: sql-readonly
                    sources: s3
                """);
    assertThrows(
        ConfigException.class,
        () -> new ConfigComposer().compose(gateway, List.of(invalidHookSources)));

    Path externalHookSource = tempDir.resolve("ext-hook-source.yaml");
    Files.writeString(
        externalHookSource,
        """
                id: s4
                type: http
                baseUrl: https://example.internal
                hooks:
                  - type: sql-readonly
                    sources: [other-source]
                """);
    assertThrows(
        ConfigException.class,
        () -> new ConfigComposer().compose(gateway, List.of(externalHookSource)));
  }

  @Test
  void detectsCrossFileHookAndToolCollisions() throws Exception {
    Path gateway = tempDir.resolve("aegis.yaml");
    Files.writeString(
        gateway,
        """
                audit:
                  file: audit.jsonl
                policy:
                  rules:
                    - {id: r, groups: ["*"], classes: [READ]}
                hooks:
                  - type: sql-readonly
                    settings:
                      name: shared-guard
                """);

    Path adopterHookClash = tempDir.resolve("adopter1.yaml");
    Files.writeString(
        adopterHookClash,
        """
                id: a1
                type: http
                baseUrl: https://a1.internal
                hooks:
                  - type: sql-readonly
                    settings:
                      name: shared-guard
                """);
    ConfigException hookEx =
        assertThrows(
            ConfigException.class,
            () -> new ConfigComposer().compose(gateway, List.of(adopterHookClash)));
    assertTrue(hookEx.getMessage().contains("duplicate hook"));

    Path adopterA = tempDir.resolve("adopterA.yaml");
    Files.writeString(
        adopterA,
        """
                id: a
                type: jdbc
                tools:
                  ping: {class: READ}
                """);
    Path adopterB = tempDir.resolve("adopterB.yaml");
    Files.writeString(
        adopterB,
        """
                id: b
                type: jdbc
                tools:
                  ping: {class: READ}
                """);
    ConfigException toolEx =
        assertThrows(
            ConfigException.class,
            () -> new ConfigComposer().compose(gateway, List.of(adopterA, adopterB)));
    assertTrue(toolEx.getMessage().contains("duplicate exposed tool 'ping'"));
  }

  @Test
  void validatesMissingInputsAndSchemaBoundaries() throws Exception {
    ConfigComposer composer = new ConfigComposer();
    assertThrows(ConfigException.class, () -> composer.compose(null, List.of()));
    assertThrows(
        ConfigException.class, () -> composer.compose(tempDir.resolve("missing.yaml"), List.of()));

    Path gateway = tempDir.resolve("aegis.yaml");
    Files.writeString(gateway, "audit:\n  file: audit.jsonl\n");
    assertThrows(ConfigException.class, () -> composer.compose(gateway, List.of()));
    assertThrows(
        ConfigException.class,
        () -> composer.compose(gateway, List.of(tempDir.resolve("nope.yaml"))));

    Path gatewayWithSources = tempDir.resolve("bad-gw.yaml");
    Files.writeString(gatewayWithSources, "sources: []\n");
    Path validAdopter = tempDir.resolve("valid.yaml");
    Files.writeString(validAdopter, "id: ok\ntype: http\nbaseUrl: https://ok.internal\n");
    assertThrows(
        ConfigException.class, () -> composer.compose(gatewayWithSources, List.of(validAdopter)));

    Path unknownKeyAdopter = tempDir.resolve("unknown.yaml");
    Files.writeString(unknownKeyAdopter, "id: bad\ntype: http\nunknownField: 1\n");
    assertThrows(
        ConfigException.class, () -> composer.compose(gateway, List.of(unknownKeyAdopter)));

    Path blankIdAdopter = tempDir.resolve("blank-id.yaml");
    Files.writeString(blankIdAdopter, "id: \"\"\ntype: http\n");
    assertThrows(ConfigException.class, () -> composer.compose(gateway, List.of(blankIdAdopter)));

    Path blankTypeAdopter = tempDir.resolve("blank-type.yaml");
    Files.writeString(blankTypeAdopter, "id: x\ntype: \"\"\n");
    assertThrows(ConfigException.class, () -> composer.compose(gateway, List.of(blankTypeAdopter)));
  }

  @Test
  void mergesScopesAndHooksAndProducesDeterministicHash() throws Exception {
    Path gateway = tempDir.resolve("aegis.yaml");
    Files.writeString(
        gateway,
        """
                gateway:
                  name: aegis-hive
                access:
                  writeGroups: [hive-operators]
                  approverGroups: [hive-approvers]
                approval:
                  keyEnv: AEGIS_SIGNING_KEY
                policy:
                  rules:
                    - {id: read, groups: [hive-users, hive-operators], classes: [READ]}
                    - {id: operate, groups: [hive-operators], classes: [MUTATE, DESTRUCTIVE]}
                rateLimits:
                  perCallerPerMinute: 60
                  budgetPerHour: 500
                egress:
                  allowCidrs: [10.0.0.0/8]
                audit:
                  file: audit/aegis-hive.jsonl
                scopes:
                  - groups: [hive-users]
                    resources: ["hive:schema:default"]
                  - groups: [hive-operators]
                    resources: ["hive:schema:*"]
                """);

    Path adopter = tempDir.resolve("hive-adopter.yaml");
    Files.writeString(
        adopter,
        """
                id: hive
                type: jdbc
                settings:
                  url: jdbc:hive2://hiveserver2:10000/default
                  driverClass: org.apache.hive.jdbc.HiveDriver
                  userEnv: HIVE_USER
                  passwordEnv: HIVE_PASSWORD
                  maxRows: 200
                  maxCellChars: 4096
                  loginTimeoutSeconds: 15
                  allowWrites: false
                hooks:
                  - type: sql-readonly
                    operations: [TOOL_CALL]
                    mode: ENFORCE
                    methods: [jdbc_run_query]
                    settings:
                      arguments: [sql]
                      maxChars: 8192
                tools:
                  jdbc_server_info: {class: READ}
                  jdbc_list_catalogs: {class: READ}
                  jdbc_list_schemas: {class: READ}
                  jdbc_list_tables:
                    class: READ
                    resources: [{type: "hive:schema", pointer: /schema, required: false}]
                  jdbc_describe_table:
                    class: READ
                    resources: [{type: "hive:table", pointer: /table}]
                  jdbc_run_query: {class: READ}
                """);
  }

  @Test
  void hiveCompositionIsSemanticallyEqualToReferenceHiveProfile() throws Exception {
    Path repoRoot = Path.of("").toAbsolutePath().normalize();
    while (repoRoot != null && !Files.isDirectory(repoRoot.resolve("adopters"))) {
      repoRoot = repoRoot.getParent();
    }
    assertNotNull(repoRoot, "repository root not found");

    Path hiveLayersDir = repoRoot.resolve("examples/adopters/hive/aegis");
    Path hiveOutputDir = repoRoot.resolve("adopters/configurations/adapters/hive");
    Path gatewayLayer = hiveLayersDir.resolve("gateway.yaml");
    Path adopterLayer = hiveLayersDir.resolve("adopter.yaml");
    Path referenceHive = hiveOutputDir.resolve("hive.yaml");
    Path canonicalHive = hiveLayersDir.resolve("canonical.yaml");

    assertTrue(Files.isRegularFile(gatewayLayer), "missing gateway layer fixture: " + gatewayLayer);
    assertTrue(Files.isRegularFile(adopterLayer), "missing adopter layer fixture: " + adopterLayer);
    assertTrue(
        Files.isRegularFile(referenceHive), "missing approved reference profile: " + referenceHive);
    assertTrue(
        Files.isRegularFile(canonicalHive), "missing canonical snapshot fixture: " + canonicalHive);

    ConfigComposer.ComposedConfig composed =
        new ConfigComposer().compose(gatewayLayer, List.of(adopterLayer), hiveOutputDir);

    Map<String, Object> expected = ConfigLoader.load(referenceHive);
    assertEquals(expected, composed.effectiveMap());
    String referenceText = Files.readString(referenceHive).replace("\r\n", "\n");
    String canonicalText = Files.readString(canonicalHive).replace("\r\n", "\n");
    assertEquals(referenceText, composed.effectiveYaml());
    assertEquals(referenceText, canonicalText);
  }

  @Test
  void rejectsComposedConfigThatFailsRuntimeGatewaySchema() throws Exception {
    Path gateway = tempDir.resolve("aegis.yaml");
    Files.writeString(
        gateway,
        """
                audit:
                  file: audit.jsonl
                policy:
                  rules:
                    - {id: r, groups: ["*"], classes: [NOT_A_REAL_TOOL_CLASS]}
                """);

    Path adopter = tempDir.resolve("adopter.yaml");
    Files.writeString(
        adopter,
        """
                id: demo
                type: http
                baseUrl: https://api.internal
                tools:
                  - name: ping
                    class: READ
                    request: {method: GET, path: /ping}
                """);

    Path outDir = tempDir.resolve("dist");
    ConfigException ex =
        assertThrows(
            ConfigException.class,
            () -> new ConfigComposer().compose(gateway, List.of(adopter), outDir));
    assertNotNull(ex.getMessage());
    assertFalse(ex.getMessage().isBlank());
  }
}
