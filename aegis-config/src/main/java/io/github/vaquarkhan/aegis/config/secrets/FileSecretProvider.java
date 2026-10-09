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
package io.github.vaquarkhan.aegis.config.secrets;

import io.github.vaquarkhan.aegis.config.ConfigException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@code secrets.source: file}: each name is one file in a mounted directory, the layout of a
 * Kubernetes Secret volume, Docker or Compose secrets ({@code /run/secrets}) and the Vault or
 * Secrets Store CSI agents. The value is the file's content with one trailing newline removed.
 *
 * <p>Names are restricted to letters, digits, {@code _}, {@code .} and {@code -} and must not start
 * with a dot, so a name can never leave the directory; the resolved real path is checked to stay
 * inside the directory's real path too, which keeps Kubernetes' {@code ..data} symlinks working.
 */
public final class FileSecretProvider implements Function<String, String> {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");
    static final int MAX_BYTES = 64 * 1024;

    private final Path directory;

    public FileSecretProvider(Path directory) {
        Path real;
        try {
            real = directory.toRealPath();
        } catch (IOException e) {
            throw new ConfigException("config.secrets.settings.directory " + directory + " does not exist");
        }
        if (!Files.isDirectory(real)) {
            throw new ConfigException("config.secrets.settings.directory " + directory + " is not a directory");
        }
        this.directory = real;
    }

    @Override
    public String apply(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.contains("..")) {
            return null;
        }
        Path f = directory.resolve(name);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            Path real = f.toRealPath();
            if (!real.startsWith(directory)) {
                throw new ConfigException("config.secrets: " + name + " resolves outside " + directory);
            }
            if (Files.size(real) > MAX_BYTES) {
                throw new ConfigException("config.secrets: " + name + " is larger than " + MAX_BYTES + " bytes");
            }
            String v = Files.readString(real, StandardCharsets.UTF_8);
            if (v.endsWith("\r\n")) {
                v = v.substring(0, v.length() - 2);
            } else if (v.endsWith("\n")) {
                v = v.substring(0, v.length() - 1);
            }
            return v.isEmpty() ? null : v;
        } catch (IOException e) {
            throw new UncheckedIOException("config.secrets: " + name + " could not be read", e);
        }
    }
}
