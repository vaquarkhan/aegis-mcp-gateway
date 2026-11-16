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
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code java -jar aegis-gateway-all.jar --compose --gateway-config aegis.yaml --adopter hive.yaml --out effective.yaml}
 */
final class ComposeCommand {

    private ComposeCommand() {}

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        Path gatewayConfig = null;
        List<Path> adopters = new ArrayList<>();
        Path outFile = null;

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
                default -> {
                    err.println("unknown argument for --compose: " + args[i]);
                    return Main.USAGE;
                }
            }
        }

        if (gatewayConfig == null || adopters.isEmpty() || outFile == null) {
            err.println("usage: --compose --gateway-config <aegis.yaml> --adopter <adopter.yaml> [--adopter <adopter2.yaml> ...] --out <effective.yaml>");
            return Main.USAGE;
        }

        try {
            ConfigComposer.ComposedConfig composed = new ConfigComposer().compose(gatewayConfig, adopters);
            Path target = outFile.toAbsolutePath().normalize();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, composed.effectiveYaml(), StandardCharsets.UTF_8);
            out.println("Composed " + target + " (" + composed.effectiveConfigHash() + ")");
            return Main.OK;
        } catch (ConfigException e) {
            err.println(e.getMessage());
            return Main.USAGE;
        }
    }
}