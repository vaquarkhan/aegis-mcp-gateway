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
 * Threat-model reproduction suite (perimeter configuration). Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.security.perimeter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.engine.json.Json;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreatModelCvePerimeterTest {

  static void verdict(String id, String refs, String cls, String verdict, String detail) {
    System.out.println("THREATMODEL|" + id + "|" + refs + "|" + cls + "|" + verdict + "|" + detail);
  }

  @TempDir Path dir;

  private static final Map<String, String> ENV = Map.of("DEV_TOKEN", "dev-token-0123456789");

  private static String config(String listen) {
    return "{\"listen\":"
        + listen
        + ",\"identity\":{\"type\":\"static\",\"static\":{\"tokens\":["
        + "{\"tokenEnv\":\"DEV_TOKEN\",\"actor\":\"dev\",\"groups\":[\"dev\"]}]}}}";
  }

  @Test
  void tm19_plaintextListenerOnNetworkInterface_isRefusedUnlessDeclared() {
    ConfigException e =
        assertThrows(
            ConfigException.class,
            () ->
                OwnServerSecuritySettings.parse(
                    Json.parseObject(config("{\"host\":\"0.0.0.0\",\"port\":8080}")),
                    dir,
                    ENV::get,
                    Clock.systemUTC()));
    assertTrue(e.getMessage().contains("cleartext"), e.getMessage());
    assertThrows(
        ConfigException.class,
        () ->
            OwnServerSecuritySettings.parse(
                Json.parseObject(config("{\"host\":\"10.0.0.7\",\"port\":8080}")),
                dir,
                ENV::get,
                Clock.systemUTC()));
    // Loopback stays plaintext-capable; an explicit declaration is accepted for ingress-terminated
    // TLS.
    assertEquals(
        "127.0.0.1",
        OwnServerSecuritySettings.parse(
                Json.parseObject(config("{\"host\":\"127.0.0.1\",\"port\":0}")),
                dir,
                ENV::get,
                Clock.systemUTC())
            .host());
    assertEquals(
        "0.0.0.0",
        OwnServerSecuritySettings.parse(
                Json.parseObject(
                    config("{\"host\":\"0.0.0.0\",\"port\":8080,\"allowPlaintext\":true}")),
                dir,
                ENV::get,
                Clock.systemUTC())
            .host());
    // The flag is refused where it has no effect, like every other unused key.
    assertThrows(
        ConfigException.class,
        () ->
            OwnServerSecuritySettings.parse(
                Json.parseObject(
                    config("{\"host\":\"127.0.0.1\",\"port\":0,\"allowPlaintext\":true}")),
                dir,
                ENV::get,
                Clock.systemUTC()));
    verdict(
        "TM-19",
        "OWASP MCP01/MCP07; 2026 advisory pattern: HTTP mode exposing operator credentials (CVE-2026-44969 class, 1,862 open servers Jan-2026)",
        "bearer tokens on a plaintext network listener",
        "FIXED",
        "non-loopback listen.host without tls.keystore now fails config unless listen.allowPlaintext: true is declared");
  }
}
