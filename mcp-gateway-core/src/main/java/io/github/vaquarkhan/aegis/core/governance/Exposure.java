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
package io.github.vaquarkhan.aegis.core.governance;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;

/**
 * Step 1. Decides whether a tool may be registered at all and, at call time, whether the tool the
 * client asked for is one this deployment exposes.
 *
 * <p>Exposure is checked twice on purpose. Registration filtering keeps unexposed tools out of
 * {@code tools/list} so a model never learns they exist, and the call time check defends against a
 * client that invokes a name it was never offered.
 *
 * @author Viquar Khan
 */
public final class Exposure {

    private final GatewayConfig config;

    public Exposure(GatewayConfig config) {
        this.config = config;
    }

    /** Registration time filter. */
    public boolean isExposed(ToolDef tool) {
        return isExposed(tool.name(), tool.cls());
    }

    public boolean isExposed(String toolName, ToolClass cls) {
        if (cls != ToolClass.READ && !config.writesUnlocked()) {
            return false;
        }
        return config.toolAllowed(toolName);
    }

    /** Human readable reason, used in startup logs when a tool is withheld. */
    public String reason(String toolName, ToolClass cls) {
        if (cls != ToolClass.READ && !config.writesUnlocked()) {
            return "writes locked (MCP_GW_WRITE_ENABLED and MCP_GW_APPROVAL_SECRET)";
        }
        if (!config.toolAllowed(toolName)) {
            return "not in MCP_GW_TOOLS_ALLOWED";
        }
        return "exposed";
    }
}
