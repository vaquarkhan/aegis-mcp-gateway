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
 * Threat-model reproduction suite (upstream tool metadata). Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.mcp.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThreatModelCveUpstreamNameTest {

  static void verdict(String id, String refs, String cls, String verdict, String detail) {
    System.out.println("THREATMODEL|" + id + "|" + refs + "|" + cls + "|" + verdict + "|" + detail);
  }

  private static Map<String, Object> tool(String name) {
    return Map.of("name", name, "description", "x", "inputSchema", Map.of("type", "object"));
  }

  @Test
  void tm20_hostileUpstreamToolNames_areDropped() {
    for (String bad :
        List.of(
            "../admin",
            "list/accept",
            "wipe\u0000",
            "a\tb",
            "list\u200bTopics",
            " list_topics",
            "x".repeat(129),
            "back\\slash")) {
      assertFalse(
          ClientSupport.validTool(tool(bad)),
          "should be dropped: " + bad.replace("\u0000", "<NUL>"));
    }
    for (String ok : List.of("list_topics", "kafka.describe-topic", "résumé", "ListTopics_v2")) {
      assertTrue(ClientSupport.validTool(tool(ok)), ok);
    }
    verdict(
        "TM-20",
        "MCP03 tool poisoning (metadata); CSA hidden-Unicode instruction injection 2026-03; admin-path confusion",
        "downstream server chooses hostile tool names (path separators, control/format chars, oversized)",
        "FIXED",
        "ClientSupport.validTool now rejects '/', '\\\\', control and format characters, surrounding whitespace, >128 chars");
  }
}
