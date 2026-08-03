/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.spi;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Contract every engine plugin implements. Adapters contribute capability only; all governance is
 * applied by the gateway.
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader} and must be stateless
 * with respect to callers. Per-call state belongs in {@link CallContext}.
 *
 * @author Viquar Khan
 */
public interface EngineAdapter {

    /** Stable short identifier, for example {@code flink} or {@code iceberg}. */
    String engineId();

    /**
     * Coarse family used for routing and dashboards, for example {@code streaming},
     * {@code messaging}, {@code batch}, {@code lakehouse}, {@code query}, {@code olap},
     * {@code dataflow}, {@code orchestration}, {@code storage}, {@code datastore}.
     */
    String taxonomyClass();

    List<ToolDef> tools(GatewayConfig cfg);

    List<ResourceDef> resources(GatewayConfig cfg);

    default Optional<ReadOnlyGuard> readOnlyGuard() {
        return Optional.empty();
    }

    default Optional<CredentialResolver> credentialResolver() {
        return Optional.empty();
    }

    /**
     * Hosts this adapter needs to reach. The gateway unions these with
     * {@code MCP_GW_EGRESS_ALLOW_HOSTS}; anything outside the union is denied at step 5.
     */
    Set<String> egressAllowHosts(GatewayConfig cfg);
}
