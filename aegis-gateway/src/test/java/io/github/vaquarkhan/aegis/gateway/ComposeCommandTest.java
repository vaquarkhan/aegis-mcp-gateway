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
package io.github.vaquarkhan.aegis.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComposeCommandTest {

  @TempDir Path tempDir;

  @Test
  void composesChecksDriftAndRunsPolicyTestAgainstEffectiveConfig() throws Exception {
    Path gatewayFile = tempDir.resolve("aegis.yaml");
    Files.writeString(
        gatewayFile,
        """
                gateway:
                  name: aegis-test
                audit:
                  file: ./audit.jsonl
                policy:
                  rules:
                    - id: analysts-read-only
                      effect: ALLOW
                      groups: [analysts]
                      classes: [READ]
                """);

    Path adopterFile = tempDir.resolve("adopters/hive.yaml");
    Files.createDirectories(adopterFile.getParent());
    Files.writeString(
        adopterFile,
        """
                id: hive-analytics
                type: http
                baseUrl: https://hive.internal:10001
                tools:
                  - name: run_query
                    class: READ
                    description: Run analytical SQL query
                    request:
                      method: POST
                      path: /query
                  - name: drop_table
                    class: DESTRUCTIVE
                    description: Drop table
                    request:
                      method: POST
                      path: /drop
                """);

    Path effectiveFile = tempDir.resolve("out/effective-aegis.yaml");

    // 1. --check fails before materialization
    int missingCheckExit =
        runMain(
            "--compose",
            "--gateway-config",
            gatewayFile.toString(),
            "--adopter",
            adopterFile.toString(),
            "--out",
            effectiveFile.toString(),
            "--check");
    assertEquals(Main.USAGE, missingCheckExit);

    // 2. Materialize effective config
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    int composeExit =
        Main.run(
            new String[] {
              "--compose",
              "--gateway-config",
              gatewayFile.toString(),
              "--adopter",
              adopterFile.toString(),
              "--out",
              effectiveFile.toString()
            },
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8),
            Map.<String, String>of()::get,
            false);

    assertEquals(Main.OK, composeExit, () -> "stderr: " + errBuf.toString(StandardCharsets.UTF_8));
    assertTrue(Files.isRegularFile(effectiveFile));
    assertTrue(outBuf.toString(StandardCharsets.UTF_8).contains("sha256:"));

    // 3. --check passes when up to date
    int cleanCheckExit =
        runMain(
            "--compose",
            "--gateway-config",
            gatewayFile.toString(),
            "--adopter",
            adopterFile.toString(),
            "--out",
            effectiveFile.toString(),
            "--check");
    assertEquals(Main.OK, cleanCheckExit);

    // 4. --check detects drift when output is modified
    Files.writeString(effectiveFile, Files.readString(effectiveFile) + "\n# tampered\n");
    int driftCheckExit =
        runMain(
            "--compose",
            "--gateway-config",
            gatewayFile.toString(),
            "--adopter",
            adopterFile.toString(),
            "--out",
            effectiveFile.toString(),
            "--check");
    assertEquals(Main.USAGE, driftCheckExit);

    // Re-materialize clean file for --policy-test
    assertEquals(
        Main.OK,
        runMain(
            "--compose",
            "--gateway-config",
            gatewayFile.toString(),
            "--adopter",
            adopterFile.toString(),
            "--out",
            effectiveFile.toString()));

    Path casesFile = tempDir.resolve("cases.json");
    Files.writeString(
        casesFile,
        """
                {
                  "cases": [
                    {
                      "name": "analyst can read hive",
                      "caller": {"actor": "alice", "groups": ["analysts"]},
                      "tool": "run_query",
                      "arguments": {},
                      "expect": "ALLOWED"
                    },
                    {
                      "name": "analyst denied destructive drop",
                      "caller": {"actor": "alice", "groups": ["analysts"]},
                      "tool": "drop_table",
                      "arguments": {},
                      "expect": "READONLY_CALLER"
                    }
                  ]
                }
                """);

    int policyExit = runMain("--policy-test", effectiveFile.toString(), casesFile.toString());
    assertEquals(Main.OK, policyExit);
  }

  @Test
  void refusesToOverwriteGatewayOrAdopterInputs() throws Exception {
    Path gatewayFile = tempDir.resolve("aegis.yaml");
    Files.writeString(gatewayFile, "audit:\n  file: audit.jsonl\n");
    Path adopterFile = tempDir.resolve("adopter.yaml");
    Files.writeString(adopterFile, "id: demo\ntype: http\nbaseUrl: https://demo.internal\n");

    assertEquals(
        Main.USAGE,
        runMain(
            "--compose",
            "--gateway-config",
            gatewayFile.toString(),
            "--adopter",
            adopterFile.toString(),
            "--out",
            gatewayFile.toString()));
    assertEquals(
        Main.USAGE,
        runMain(
            "--compose",
            "--gateway-config",
            gatewayFile.toString(),
            "--adopter",
            adopterFile.toString(),
            "--out",
            adopterFile.toString()));
  }

  @Test
  void returnsUsageWhenRequiredComposeFlagsAreMissing() throws Exception {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

    int exit =
        Main.run(
            new String[] {"--compose", "--gateway-config", "aegis.yaml"},
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8),
            Map.<String, String>of()::get,
            false);

    assertEquals(Main.USAGE, exit);
    assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("usage: --compose"));
  }

  private int runMain(String... args) throws Exception {
    return Main.run(
        args,
        new ByteArrayInputStream(new byte[0]),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
        Map.<String, String>of()::get,
        false);
  }
}
