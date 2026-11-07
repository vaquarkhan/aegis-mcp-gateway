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
package io.github.vaquarkhan.aegis.state.derby;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;

/**
 * Second-process probe for {@link DerbyStateStoreTest}: run in its own JVM, it tries to open the
 * state store at {@code args[0]}. Exit 0 and {@code OPENED} when the store opened, exit 2 and the
 * SQLState chain when Derby refused it.
 */
public final class DerbyLockProbe {

    private DerbyLockProbe() {}

    public static void main(String[] args) {
        try (DerbyStateStore store = new DerbyStateStore(Path.of(args[0]), Clock.systemUTC())) {
            System.out.println("OPENED");
        } catch (SQLException e) {
            StringBuilder states = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException s && s.getSQLState() != null) {
                    states.append(s.getSQLState()).append(' ');
                }
            }
            System.out.println("REFUSED " + states.toString().trim());
            System.exit(2);
        } catch (Exception e) {
            System.out.println("ERROR " + e.getClass().getName());
            System.exit(3);
        }
    }
}
