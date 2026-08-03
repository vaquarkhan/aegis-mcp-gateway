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

package io.github.vaquarkhan.aegis.adapter.flink;

import io.github.vaquarkhan.aegis.adapter.flink.client.SqlReadonlyGuard;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.observability.Metrics;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ReadOnlyGuard;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Apache Flink engine adapter. Exposes the JobManager REST API and the SQL Gateway as governed
 * MCP tools and resources. Only the JDK HTTP client is used, so no Flink runtime jar is needed
 * and the adapter works across Flink versions that keep the REST contract.
 *
 * @author Viquar Khan
 */
public final class FlinkAdapter implements EngineAdapter {

    public static final String ENGINE_ID = "flink";
    public static final String TAXONOMY_CLASS = "streaming";

    private static final Logger LOG = LoggerFactory.getLogger(FlinkAdapter.class);

    private final SqlReadonlyGuard sqlGuard = new SqlReadonlyGuard();
    private final Metrics metrics = new Metrics();

    private GatewayConfig factoryConfig;
    private FlinkToolFactory factory;

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public String taxonomyClass() {
        return TAXONOMY_CLASS;
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        LOG.debug("building flink tools rest={} gateway={}",
                FlinkConfigKeys.restUrl(cfg), FlinkConfigKeys.gatewayUrl(cfg));
        return factory(cfg).tools();
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        return factory(cfg).resources();
    }

    @Override
    public Optional<ReadOnlyGuard> readOnlyGuard() {
        return Optional.of(sqlGuard);
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        return FlinkConfigKeys.egressHosts(cfg);
    }

    /** Tools and resources share one factory so that both reuse the same HTTP clients. */
    private synchronized FlinkToolFactory factory(GatewayConfig cfg) {
        if (factory == null || factoryConfig != cfg) {
            factoryConfig = cfg;
            factory = new FlinkToolFactory(cfg, sqlGuard, metrics);
        }
        return factory;
    }
}
