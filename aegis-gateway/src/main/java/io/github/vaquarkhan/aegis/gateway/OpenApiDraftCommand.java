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

import io.github.vaquarkhan.aegis.config.ConfigLoader;
import io.github.vaquarkhan.aegis.config.openapi.OpenApiDraft;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * {@code --openapi-draft <openapi.yaml|json> <draft.yaml> [--source-id <id>] [--base-url <url>]}:
 * writes a draft configuration with one DESTRUCTIVE tool per operation, for a person to review.
 * Never overwrites an existing file, and never contacts the API it describes.
 */
final class OpenApiDraftCommand {

    private static final String USAGE =
            "--openapi-draft <openapi.yaml|openapi.json> <draft.yaml> [--source-id <id>] [--base-url <url>]";

    private OpenApiDraftCommand() {}

    static int run(String[] a, PrintStream out, PrintStream err) {
        if (a.length < 2) {
            return usage(err);
        }
        String sourceId = null;
        String baseUrl = null;
        for (int i = 2; i < a.length; i++) {
            if (i + 1 >= a.length) {
                return usage(err);
            }
            switch (a[i]) {
                case "--source-id" -> sourceId = a[++i];
                case "--base-url" -> baseUrl = a[++i];
                default -> {
                    return usage(err);
                }
            }
        }
        Path spec = Path.of(a[0]);
        Path target = Path.of(a[1]);
        if (Files.exists(target)) {
            err.println("refusing to overwrite " + target);
            return Main.USAGE;
        }
        try {
            OpenApiDraft.Draft d = OpenApiDraft.generate(OpenApiDraft.read(spec), new OpenApiDraft.Options(sourceId, baseUrl));
            // The draft must load as Aegis YAML before it is written.
            ConfigLoader.parseYaml(d.yaml(), target.toString());
            Files.writeString(target, d.yaml(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            out.println("wrote " + target + " with " + d.tools() + " tools, all DESTRUCTIVE and denied by policy until reviewed; "
                    + d.warnings().size() + " warnings (listed at the top of the file)");
            return Main.OK;
        } catch (FileAlreadyExistsException e) {
            err.println("refusing to overwrite " + e.getFile());
            return Main.USAGE;
        } catch (IOException | RuntimeException e) {
            err.println("BROKEN " + e.getMessage());
            return Main.BROKEN;
        }
    }

    private static int usage(PrintStream err) {
        err.println("usage: " + USAGE);
        return Main.USAGE;
    }
}
