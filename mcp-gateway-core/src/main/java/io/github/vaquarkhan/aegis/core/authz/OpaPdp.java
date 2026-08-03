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
package io.github.vaquarkhan.aegis.core.authz;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Open Policy Agent decision point.
 *
 * <p>Stub for 0.1.0 that denies every call. The class exists so {@code MCP_GW_PDP=opa} selects a
 * real object rather than failing at startup, and denying is the only safe answer while no remote
 * decision is actually requested.
 *
 * <p>TODO: POST the principal, action and resource document to the OPA data API, apply a decision
 * timeout, cache short lived allow decisions, and fail closed on transport or parse errors. See
 * DESIGN section 5 and LLD section 5.
 *
 * @author Viquar Khan
 */
public final class OpaPdp implements PolicyDecisionPoint {

    private static final Logger LOG = LoggerFactory.getLogger(OpaPdp.class);

    private final String decisionUrl;
    private final AtomicBoolean warned = new AtomicBoolean();

    public OpaPdp(String decisionUrl) {
        this.decisionUrl = decisionUrl;
        LOG.warn("OPA PDP is a stub and denies every call (decisionUrl={})",
                decisionUrl == null ? "-" : decisionUrl);
    }

    public String decisionUrl() {
        return decisionUrl;
    }

    @Override
    public boolean allows(CallerIdentity caller, String tool, Map<String, Object> args) {
        if (warned.compareAndSet(false, true)) {
            LOG.error("MCP_GW_PDP=opa is not implemented; denying every call including {}", tool);
        }
        return false;
    }
}
