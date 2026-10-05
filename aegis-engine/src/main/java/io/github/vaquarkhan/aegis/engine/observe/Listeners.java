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
package io.github.vaquarkhan.aegis.engine.observe;

import io.github.vaquarkhan.aegis.engine.spi.DecisionListener;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delivers decision events. A failing listener never affects the decision; its first failure is
 * logged at WARNING (with the stack), later ones at DEBUG, so a broken metrics bridge cannot flood
 * the log on every request.
 */
public final class Listeners {

    private static final System.Logger LOG = System.getLogger(Listeners.class.getName());
    private static final Set<Class<?>> WARNED = ConcurrentHashMap.newKeySet();

    private Listeners() {}

    public static void notify(List<DecisionListener> listeners, DecisionListener.Event event) {
        for (DecisionListener l : listeners) {
            try {
                l.onDecision(event);
            } catch (RuntimeException e) {
                if (WARNED.add(l.getClass())) {
                    LOG.log(System.Logger.Level.WARNING, "decision listener " + l.getClass().getName()
                            + " failed; ignored (further failures are logged at DEBUG)", e);
                } else {
                    LOG.log(System.Logger.Level.DEBUG, "decision listener failed again: " + e);
                }
            }
        }
    }
}
