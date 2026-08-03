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
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import java.util.Map;

/**
 * Step 3 of the interceptor chain. Implementations decide whether a fully identified call is
 * permitted by organisational policy.
 *
 * <p>The decision signature is {@code allows(caller, tool, args)} as in LLD section 5, so an
 * external engine such as Cedar or OPA can be handed exactly the principal, action and resource
 * triple it expects without depending on the gateway's own call carrier.
 *
 * <p>Implementations must fail closed: if the policy source cannot be read or evaluated, deny.
 *
 * @author Viquar Khan
 */
@FunctionalInterface
public interface PolicyDecisionPoint {

    boolean allows(CallerIdentity caller, String tool, Map<String, Object> args);

    /** Convenience overload used by the interceptor chain. */
    default boolean allows(CallContext ctx) {
        return ctx != null && allows(ctx.caller(), ctx.toolName(), ctx.arguments());
    }

    /** Permissive decision point used only when no policy source is configured. */
    static PolicyDecisionPoint allowAll() {
        return (caller, tool, args) -> true;
    }

    /** Deny everything. Used when a configured policy source is unusable. */
    static PolicyDecisionPoint denyAll() {
        return (caller, tool, args) -> false;
    }
}
