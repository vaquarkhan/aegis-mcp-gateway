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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactAdmissionTest {

  @TempDir Path dir;

  private static String digest(byte[] bytes) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private ConfigNode source(Object artifacts) {
    return new ConfigNode("config.sources[0]", Map.of("artifacts", artifacts));
  }

  @Test
  void matchingRegularFileIsVerifiedAndRelativePathIsResolvedFromConfig() throws Exception {
    ConfigNode none = source(List.of());
    assertTrue(ArtifactAdmission.verify(none, dir).isEmpty());
    none.finish();

    byte[] bytes = "reviewed-server".getBytes(StandardCharsets.UTF_8);
    Files.write(dir.resolve("server.bin"), bytes);
    ConfigNode source =
        source(List.of(Map.of("path", "server.bin", "sha256", digest(bytes), "role", "server")));
    List<ArtifactAdmission.Verified> verified = ArtifactAdmission.verify(source, dir);
    source.finish();
    assertEquals(1, verified.size());
    assertEquals(dir.resolve("server.bin").toRealPath(), verified.get(0).path());
    assertEquals("server", verified.get(0).role());
  }

  @Test
  void mismatchMissingDuplicateAndBadDigestFailClosed() throws Exception {
    byte[] bytes = "reviewed-server".getBytes(StandardCharsets.UTF_8);
    Files.write(dir.resolve("server.bin"), bytes);
    ConfigException mismatch =
        assertThrows(
            ConfigException.class,
            () ->
                ArtifactAdmission.verify(
                    source(
                        List.of(
                            Map.of("path", "server.bin", "sha256", "sha256:" + "0".repeat(64)))),
                    dir));
    assertTrue(
        mismatch.getMessage().contains("does not match")
            && mismatch.getMessage().contains("actual sha256:"),
        mismatch.getMessage());

    ConfigException missing =
        assertThrows(
            ConfigException.class,
            () ->
                ArtifactAdmission.verify(
                    source(List.of(Map.of("path", "missing.bin", "sha256", digest(bytes)))), dir));
    assertTrue(missing.getMessage().contains("cannot be verified"), missing.getMessage());

    ConfigException duplicate =
        assertThrows(
            ConfigException.class,
            () ->
                ArtifactAdmission.verify(
                    source(
                        List.of(
                            Map.of("path", "server.bin", "sha256", digest(bytes)),
                            Map.of("path", "server.bin", "sha256", digest(bytes)))),
                    dir));
    assertTrue(duplicate.getMessage().contains("duplicates"), duplicate.getMessage());

    ConfigException bad =
        assertThrows(
            ConfigException.class,
            () ->
                ArtifactAdmission.verify(
                    source(List.of(Map.of("path", "server.bin", "sha256", "md5:bad"))), dir));
    assertTrue(bad.getMessage().contains("64 lower-case hex"), bad.getMessage());

    ConfigNode prefixless =
        source(
            List.of(
                Map.of(
                    "path",
                    dir.resolve("server.bin").toString(),
                    "sha256",
                    digest(bytes).substring("sha256:".length()))));
    assertEquals("server", ArtifactAdmission.verify(prefixless, dir).get(0).role());
    prefixless.finish();

    ConfigException directory =
        assertThrows(
            ConfigException.class,
            () ->
                ArtifactAdmission.verify(
                    source(List.of(Map.of("path", dir.toString(), "sha256", digest(bytes)))), dir));
    assertTrue(directory.getMessage().contains("regular file"), directory.getMessage());
  }
}
