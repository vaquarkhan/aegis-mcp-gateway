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

import io.github.vaquarkhan.aegis.config.ConfigComposer;
import io.github.vaquarkhan.aegis.config.ConfigException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code java -jar aegis-gateway-all.jar --compose --gateway-config aegis.yaml --adopter hive.yaml
 * --out effective.yaml [--check]}
 */
final class ComposeCommand {

  private ComposeCommand() {}

  static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
    Path gatewayConfig = null;
    List<Path> adopters = new ArrayList<>();
    Path outFile = null;
    boolean checkOnly = false;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--gateway-config" -> {
          if (i + 1 >= args.length) {
            err.println("--gateway-config needs a file");
            return Main.USAGE;
          }
          gatewayConfig = Path.of(args[++i]);
        }
        case "--adopter" -> {
          if (i + 1 >= args.length) {
            err.println("--adopter needs a file");
            return Main.USAGE;
          }
          adopters.add(Path.of(args[++i]));
        }
        case "--out" -> {
          if (i + 1 >= args.length) {
            err.println("--out needs a file");
            return Main.USAGE;
          }
          outFile = Path.of(args[++i]);
        }
        case "--check" -> checkOnly = true;
        default -> {
          err.println("unknown argument for --compose: " + args[i]);
          return Main.USAGE;
        }
      }
    }

    if (gatewayConfig == null || adopters.isEmpty() || outFile == null) {
      err.println(
          "usage: --compose --gateway-config <aegis.yaml> --adopter <adopter.yaml> [--adopter <adopter2.yaml> ...] --out <effective.yaml> [--check]");
      return Main.USAGE;
    }

    try {
      Path target = outFile.toAbsolutePath().normalize();
      ensureOutputDoesNotOverwriteInputs(target, gatewayConfig, adopters);

      ConfigComposer.ComposedConfig composed =
          new ConfigComposer().compose(gatewayConfig, adopters, target.getParent());

      if (checkOnly) {
        if (!Files.isRegularFile(target)) {
          err.println("composed config drift: output file does not exist: " + target);
          return Main.USAGE;
        }
        String existing = Files.readString(target, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String expected = composed.effectiveYaml().replace("\r\n", "\n");
        if (!existing.equals(expected)) {
          err.println(
              "composed config drift detected for "
                  + target
                  + " (expected "
                  + composed.effectiveConfigHash()
                  + ")");
          return Main.USAGE;
        }
        out.println("Up to date: " + target + " (" + composed.effectiveConfigHash() + ")");
        return Main.OK;
      }

      atomicWriteUtf8(target, composed.effectiveYaml());
      out.println("Composed " + target + " (" + composed.effectiveConfigHash() + ")");
      return Main.OK;
    } catch (ConfigException e) {
      err.println(e.getMessage());
      return Main.USAGE;
    }
  }

  private static void ensureOutputDoesNotOverwriteInputs(
      Path target, Path gatewayConfig, List<Path> adopters) {
    if (samePath(target, gatewayConfig)) {
      throw new ConfigException("--out must not overwrite --gateway-config: " + target);
    }
    for (Path adopter : adopters) {
      if (samePath(target, adopter)) {
        throw new ConfigException("--out must not overwrite --adopter input: " + target);
      }
    }
  }

  private static boolean samePath(Path a, Path b) {
    Path normA = a.toAbsolutePath().normalize();
    Path normB = b.toAbsolutePath().normalize();
    if (normA.equals(normB)) {
      return true;
    }
    try {
      if (Files.exists(normA) && Files.exists(normB)) {
        return Files.isSameFile(normA, normB);
      }
    } catch (IOException ignored) {
      // Fall through to normalized equality
    }
    return false;
  }

  private static void atomicWriteUtf8(Path target, String content) throws IOException {
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path tempDir = parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    Path tempFile = Files.createTempFile(tempDir, ".aegis-compose.", ".tmp");
    try {
      Files.writeString(tempFile, content, StandardCharsets.UTF_8);
      try {
        Files.move(
            tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }
}
