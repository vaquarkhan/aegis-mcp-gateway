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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Verifies local executable, script and plug-in artifacts before a source is constructed. The
 * expected digest is part of the source block, so a host-anchored signed server catalog binds it
 * through {@code sourceDigest} without changing catalog version 1. Symbolic links are refused to
 * prevent a reviewed path from being swapped to different bytes after review.
 */
public final class ArtifactAdmission {

  private static final Pattern SHA256 = Pattern.compile("(?:sha256:)?[0-9a-f]{64}");
  private static final int BUFFER = 64 * 1024;

  private ArtifactAdmission() {}

  public record Verified(Path path, String digest, String role) {}

  public static List<Verified> verify(ConfigNode source, Path baseDir) {
    List<Verified> out = new ArrayList<>();
    Set<Path> unique = new TreeSet<>();
    for (ConfigNode item : source.children("artifacts")) {
      String value = item.requiredString("path");
      String expected = item.requiredString("sha256").toLowerCase(java.util.Locale.ROOT);
      String role = item.string("role", "server");
      if (!SHA256.matcher(expected).matches()) {
        throw item.error(
            "sha256", "must be 64 lower-case hex characters, optionally prefixed by sha256:");
      }
      if (!expected.startsWith("sha256:")) {
        expected = "sha256:" + expected;
      }
      Path configured = Path.of(value);
      Path path =
          configured.isAbsolute()
              ? configured.normalize()
              : baseDir.resolve(configured).normalize();
      try {
        if (Files.isSymbolicLink(path)) {
          throw item.error("path", "must not be a symbolic link: " + path);
        }
        Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
          throw item.error("path", "must be a regular file: " + path);
        }
        if (!unique.add(real)) {
          throw item.error("path", "duplicates another admitted artifact: " + real);
        }
        String actual = sha256(real);
        if (!MessageDigest.isEqual(
            expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
          throw item.error("sha256", "does not match " + path + " (actual " + actual + ")");
        }
        out.add(new Verified(real, actual, role));
      } catch (IOException e) {
        throw item.error("path", "cannot be verified: " + path + " (" + e.getMessage() + ")");
      }
    }
    return List.copyOf(out);
  }

  private static String sha256(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM has no SHA-256", e);
    }
    byte[] buffer = new byte[BUFFER];
    try (InputStream in = Files.newInputStream(path)) {
      int n;
      while ((n = in.read(buffer)) >= 0) {
        if (n > 0) {
          digest.update(buffer, 0, n);
        }
      }
    }
    return "sha256:" + HexFormat.of().formatHex(digest.digest());
  }
}
