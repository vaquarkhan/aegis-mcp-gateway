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
package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.authz.BuiltinPolicyEngine;
import io.github.vaquarkhan.aegis.core.authz.CedarPdp;
import io.github.vaquarkhan.aegis.core.authz.OpaPdp;
import io.github.vaquarkhan.aegis.core.authz.PolicyDecisionPoint;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class PolicyDecisionPointTest {

    private static CallerIdentity ops() {
        return new CallerIdentity("ops", Set.of("*"), false);
    }

    private static CallContext ctx(String tool, Map<String, Object> args) {
        return CallContext.of(tool, ToolClass.READ, args, ops(), "trace-0");
    }

    @Test
    void callContextOverloadDelegatesToTheLldSignature() {
        PolicyDecisionPoint pdp = BuiltinPolicyEngine.parse(List.of("deny tool list_*"));
        assertFalse(pdp.allows(ctx("list_jobs", Map.of())));
        assertFalse(pdp.allows(ops(), "list_jobs", Map.of()));
        assertTrue(pdp.allows(ops(), "get_job", Map.of()));
    }

    @Test
    void builtinEngineDeniesByToolCallerAndResource() {
        PolicyDecisionPoint pdp = BuiltinPolicyEngine.parse(List.of(
                "deny caller robot-*",
                "deny resource prod.*"));
        assertTrue(pdp.allows(ops(), "get_job", Map.of("table", "dev.events")));
        assertFalse(pdp.allows(ops(), "get_job", Map.of("table", "prod.events")));
        assertFalse(pdp.allows(
                new CallerIdentity("robot-7", Set.of("*"), true), "get_job", Map.of()));
    }

    @Test
    void unreadablePolicyFileFailsClosed() {
        BuiltinPolicyEngine engine = BuiltinPolicyEngine.load("no/such/policy.rules");
        assertTrue(engine.failClosed());
        assertFalse(engine.allows(ops(), "get_job", Map.of()));
    }

    @Test
    void cedarAndOpaStubsDenyEverything() {
        assertFalse(new CedarPdp("policy.cedar").allows(ops(), "list_jobs", Map.of()));
        assertFalse(new OpaPdp("http://opa:8181/v1/data/aegis/allow")
                .allows(ops(), "list_jobs", Map.of()));
        assertFalse(new CedarPdp(null).allows(ctx("list_jobs", Map.of())));
    }

    @Test
    void nullContextIsDeniedEvenByAPermissiveEngine() {
        assertFalse(PolicyDecisionPoint.allowAll().allows((CallContext) null));
        assertTrue(PolicyDecisionPoint.allowAll().allows(ops(), "anything", Map.of()));
    }
}
