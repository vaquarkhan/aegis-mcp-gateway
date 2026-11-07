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
package io.github.vaquarkhan.aegis.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.ConfigLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.zookeeper.server.admin.Commands;
import org.junit.jupiter.api.Test;

/**
 * Every {@code /commands/<name>} path in the canonical {@code adopters/configurations/adapters/zookeeper/zookeeper.yaml} must name a command
 * Apache ZooKeeper actually registers.
 *
 * <p>This exists because a hand-written contract harness cannot catch an invented endpoint. The harness in
 * {@link AdapterConfigsTest} is written next to the config, so if both use the same wrong assumption,
 * That is exactly how this project shipped HTTP routes that do not exist.
 *
 * <p>The oracle here is not a hand-written fixture or a document: it is
 * {@link Commands}, ZooKeeper's own command registry, resolved from the ZooKeeper jar on the test
 * classpath. {@code Commands.getCommand(name)} is what the AdminServer itself calls to dispatch a
 * request, so a name it cannot resolve is a name the real AdminServer would answer with an error. A
 * typo, a plural that does not exist, or a command removed in a later ZooKeeper release all fail the
 * build here rather than in somebody's cluster.
 */
class ZooKeeperAdminCommandsTest {

    /** {@code /commands/<name>} with nothing after the name, which is the shape the AdminServer serves. */
    private static final Pattern COMMAND_PATH = Pattern.compile("^/commands/([A-Za-z0-9_]+)$");

    /** Every request path declared by an http source in the ZooKeeper adapter config. */
    static List<String> declaredPaths() throws Exception {
        Map<String, Object> cfg = ConfigLoader.load(AdopterConfigurations.adapter("zookeeper"));
        List<String> paths = new ArrayList<>();
        for (Object srcNode : asList(cfg.get("sources"))) {
            Map<String, Object> src = asMap(srcNode);
            if (!"http".equals(src.get("type"))) {
                continue;
            }
            for (Object toolNode : asList(src.get("tools"))) {
                Map<String, Object> request = asMap(asMap(toolNode).get("request"));
                Object path = request.get("path");
                assertNotNull(path, "every http tool declares a request path: " + toolNode);
                paths.add(String.valueOf(path));
            }
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    @Test
    void everyDeclaredAdminServerPathIsACommandZooKeeperRegisters() throws Exception {
        List<String> paths = declaredPaths();
        assertFalse(paths.isEmpty(), "the ZooKeeper config should declare at least one AdminServer path");
        List<String> bad = new ArrayList<>();
        for (String path : paths) {
            Matcher m = COMMAND_PATH.matcher(path);
            if (!m.matches()) {
                bad.add(path + " is not an AdminServer command path; the ZooKeeper data tree is not HTTP, "
                        + "so a non-/commands path here means an endpoint that does not exist");
                continue;
            }
            String name = m.group(1);
            if (Commands.getCommand(name) == null) {
                bad.add(path + " -> ZooKeeper registers no command named '" + name + "'. Registered names: "
                        + new java.util.TreeSet<>(Commands.getPrimaryNames()));
            }
        }
        assertTrue(bad.isEmpty(), "ZooKeeper AdminServer paths this config invents:\n  " + String.join("\n  ", bad));
    }

    /**
     * Guards the guard. If ZooKeeper ever changed {@code Commands} so that unknown names resolved to
     * something non-null, the check above would pass for anything and quietly stop working.
     */
    @Test
    void theRegistryActuallyRejectsANameZooKeeperDoesNotHave() {
        assertNotNull(Commands.getCommand("ruok"), "ruok is a real ZooKeeper command");
        assertTrue(Commands.getCommand("definitely_not_a_zookeeper_command") == null,
                "the registry must return null for a name ZooKeeper does not have, or this test proves nothing");
    }
}
