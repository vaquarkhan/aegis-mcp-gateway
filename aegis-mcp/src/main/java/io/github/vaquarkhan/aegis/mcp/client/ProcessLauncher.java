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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Starts an stdio MCP process from an argument vector without shell interpolation. Complete-control
 * mode requires a launcher whose {@link #sandboxed()} property is true. The built-in prefix launcher
 * puts a reviewed external sandbox command in front of the server argv; Aegis artifact admission must
 * verify the launcher and server files separately.
 */
public interface ProcessLauncher {

    Process start(List<String> command, Map<String, String> environment, Path workingDirectory) throws IOException;

    /** Stable name bound into configuration and audit. */
    String profile();

    /** Whether this launcher establishes an external sandbox boundary. */
    boolean sandboxed();

    static ProcessLauncher direct() {
        return Direct.INSTANCE;
    }

    static ProcessLauncher prefixed(String profile, List<String> prefix) {
        if (profile == null || !profile.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("sandbox profile must be a 1..128 character identifier");
        }
        if (prefix == null || prefix.isEmpty() || prefix.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new IllegalArgumentException("sandbox commandPrefix must contain non-blank argv elements");
        }
        return new Prefix(profile, List.copyOf(prefix));
    }

    /** Creates a ProcessBuilder with only the already-minimized environment supplied by StdioMcpClient. */
    private static Process process(List<String> command, Map<String, String> environment, Path workingDirectory)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(environment);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        return builder.start();
    }

    final class Direct implements ProcessLauncher {
        private static final Direct INSTANCE = new Direct();
        private Direct() {}

        @Override
        public Process start(List<String> command, Map<String, String> environment, Path workingDirectory)
                throws IOException {
            return process(command, environment, workingDirectory);
        }

        @Override
        public String profile() {
            return "direct";
        }

        @Override
        public boolean sandboxed() {
            return false;
        }
    }

    record Prefix(String profile, List<String> prefix) implements ProcessLauncher {
        @Override
        public Process start(List<String> command, Map<String, String> environment, Path workingDirectory)
                throws IOException {
            List<String> argv = new ArrayList<>(prefix.size() + command.size());
            argv.addAll(prefix);
            argv.addAll(command);
            return process(argv, environment, workingDirectory);
        }

        @Override
        public boolean sandboxed() {
            return true;
        }
    }
}
