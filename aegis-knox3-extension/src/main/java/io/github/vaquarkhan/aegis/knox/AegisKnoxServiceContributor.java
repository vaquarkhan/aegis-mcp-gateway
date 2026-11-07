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
package io.github.vaquarkhan.aegis.knox;

import org.apache.knox.gateway.deploy.DeploymentContext;
import org.apache.knox.gateway.jersey.JerseyServiceDeploymentContributorBase;
import org.apache.knox.gateway.topology.Service;
import org.apache.knox.gateway.topology.Version;

/**
 * Registers Aegis as a Knox native Service API (JAX-RS), the same deployment pattern as Knox's own
 * KnoxToken service: one URL pattern, so every MCP request passes through the topology's full
 * provider chain (authentication, identity assertion, authorization) before reaching Aegis.
 *
 * <p>Topology:
 * <pre>
 * &lt;service&gt;
 *   &lt;role&gt;AEGIS_MCP&lt;/role&gt;
 *   &lt;param&gt;&lt;name&gt;aegis.config&lt;/name&gt;&lt;value&gt;/etc/knox/conf/aegis/aegis.json&lt;/value&gt;&lt;/param&gt;
 * &lt;/service&gt;
 * </pre>
 * Endpoint: {@code https://<knox>/gateway/<topology>/aegis-mcp/v1/mcp}.
 */
public final class AegisKnoxServiceContributor extends JerseyServiceDeploymentContributorBase {

    public static final String ROLE = "AEGIS_MCP";

    @Override
    public String getRole() {
        return ROLE;
    }

    @Override
    public String getName() {
        return "aegis-mcp";
    }

    @Override
    public Version getVersion() {
        return new Version("2.0.0");
    }

    @Override
    protected String[] getPackages() {
        return new String[] {"io.github.vaquarkhan.aegis.knox.rs"};
    }

    @Override
    protected String[] getPatterns() {
        return new String[] {"aegis-mcp/v1/**?**"};
    }

    @Override
    public void contributeService(DeploymentContext context, Service service) throws Exception {
        super.contributeService(context, service);
        context.getWebAppDescriptor().createListener().listenerClass(AegisKnoxLifecycle.class.getName());
    }
}
