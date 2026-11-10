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
package io.github.vaquarkhan.aegis.mcp.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompleteControlStdioTest {

  static final class RecordingSandbox implements ProcessLauncher {
    final List<Process> started = new ArrayList<>();

    @Override
    public Process start(
        List<String> command, Map<String, String> environment, Path workingDirectory)
        throws IOException {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.environment().clear();
      builder.environment().putAll(environment);
      if (workingDirectory != null) {
        builder.directory(workingDirectory.toFile());
      }
      Process process = builder.start();
      started.add(process);
      return process;
    }

    @Override
    public String profile() {
      return "test-sandbox";
    }

    @Override
    public boolean sandboxed() {
      return true;
    }
  }

  private static List<String> command() {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    return List.of(
        java,
        "-cp",
        System.getProperty("java.class.path"),
        "io.github.vaquarkhan.aegis.mcp.testing.TestStdioMcpServer",
        "modern");
  }

  @Test
  void sandboxRequiredRefusesTheDirectLauncher() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new StdioMcpClient(
                    command(),
                    Map.of(),
                    null,
                    HttpMcpClient.Era.MODERN,
                    null,
                    1 << 20,
                    ProcessLauncher.direct(),
                    true));
    assertTrue(e.getMessage().contains("sandboxed"), e.getMessage());

    ProcessLauncher prefix =
        ProcessLauncher.prefixed("runsc-restricted-v1", List.of("runsc", "exec", "sandbox"));
    assertTrue(prefix.sandboxed());
    assertTrue(prefix.profile().contains("restricted"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProcessLauncher.prefixed("bad profile", List.of("x")));
  }

  @Test
  void timeoutKillsTheChildAndNextCallStartsACleanProcess() throws Exception {
    RecordingSandbox launcher = new RecordingSandbox();
    try (StdioMcpClient client =
        new StdioMcpClient(
            command(), Map.of(), null, HttpMcpClient.Era.MODERN, null, 1 << 20, launcher, true)) {
      client.listTools(Map.of(), 5_000);
      Process first = launcher.started.get(0);
      McpClient.McpCallException timeout =
          assertThrows(
              McpClient.McpCallException.class,
              () -> client.callTool("hang", Map.of(), Map.of(), 100));
      assertTrue(timeout.timeout(), timeout.getMessage());
      first.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      assertFalse(first.isAlive(), "timed-out child must be gone before another call");

      Map<String, Object> echo = client.callTool("echo", Map.of("text", "after"), Map.of(), 5_000);
      assertTrue(String.valueOf(echo).contains("echo:after"), String.valueOf(echo));
      assertTrue(launcher.started.size() >= 2, "next call starts a clean child");
      assertNotSame(first, launcher.started.get(launcher.started.size() - 1));
    }
    assertTrue(launcher.started.stream().noneMatch(Process::isAlive));
  }
}
