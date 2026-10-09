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
package io.github.vaquarkhan.aegis.config.spi;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;

/**
 * Optional durable governance-state provider, selected by {@code state.type} and discovered through
 * {@link java.util.ServiceLoader}.
 *
 * <p>The provider owns only durable state: approval and receipt nonces, pending approvals, rate/budget
 * windows and catalog pins. It cannot supply policy, expose tools, or relax any control. Every method of
 * the returned {@link StateStore} must preserve the atomic semantics documented by that interface.
 */
public interface StateStoreFactory {

    /** Configuration value, for example {@code derby-local}. Must not be {@code memory}. */
    String type();

    /**
     * Creates the store from the provider-specific {@code state.settings} object.
     *
     * @param settings strict provider-specific settings; all supported keys must be consumed
     * @param context build environment including the configuration directory and clock
     */
    StateStore create(ConfigNode settings, BuildContext context) throws Exception;
}
