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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.auth.BearerAuthFilter;
import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.auth.TokenRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class BearerAuthTest {

    private static final String TOKEN = "s3cr3t-token";

    @Test
    void acceptsTheConfiguredSharedToken() {
        BearerAuthFilter filter = new BearerAuthFilter(TOKEN);
        assertTrue(filter.resolve("Bearer " + TOKEN).isPresent());
    }

    @Test
    void rejectsMissingWrongOrMalformedHeaders() {
        BearerAuthFilter filter = new BearerAuthFilter(TOKEN);
        assertTrue(filter.resolve(null).isEmpty());
        assertTrue(filter.resolve("").isEmpty());
        assertTrue(filter.resolve("Bearer wrong").isEmpty());
        assertTrue(filter.resolve(TOKEN).isEmpty(), "the scheme prefix is part of the comparison");
        assertTrue(filter.resolve("Basic " + TOKEN).isEmpty());
        assertTrue(filter.resolve("Bearer " + TOKEN + "x").isEmpty());
    }

    @Test
    void carriesConfiguredScopesAndReadonlyFlag() {
        BearerAuthFilter filter = new BearerAuthFilter(TOKEN, "ops", Set.of("job-1"), true);
        CallerIdentity caller = filter.resolve("Bearer " + TOKEN).orElseThrow();
        assertEquals("ops", caller.callerId());
        assertTrue(caller.readonly());
        assertTrue(caller.scopeAllowed("job-1"));
        assertFalse(caller.scopeAllowed("job-2"));
    }

    @Test
    void registryModeResolvesPerCallerIdentity() {
        TokenRegistry registry = TokenRegistry.parse(List.of(
                "ops : " + TokenRegistry.sha256Hex(TOKEN) + " : job-1 : false"), "test");
        BearerAuthFilter filter = new BearerAuthFilter(registry);
        CallerIdentity caller = filter.resolve("bearer " + TOKEN).orElseThrow();
        assertEquals("ops", caller.callerId());
        assertTrue(filter.resolve("Bearer nope").isEmpty());
        assertTrue(filter.resolve("Basic " + TOKEN).isEmpty());
    }

    @Test
    void refusesToConstructWithoutACredentialSource() {
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthFilter((String) null));
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthFilter("  "));
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthFilter((TokenRegistry) null));
    }

    @Test
    void emptyCallerScopeSetDeniesEveryResource() {
        CallerIdentity caller = new CallerIdentity("misconfigured", Set.of(), false);
        assertFalse(caller.scopeAllowed("job-1"));
        assertFalse(caller.jobAllowed("job-1"));
        assertFalse(caller.jarAllowed("jar-1"));
        assertTrue(caller.scopeAllowed(null), "unscoped calls are not resource checked");
    }

    @Test
    void legacyConstructorMirrorsScopesOntoJobsAndJars() {
        CallerIdentity caller = new CallerIdentity("ops", Set.of("job-1"), false);
        assertEquals("ops", caller.subject());
        assertEquals(caller.scopes(), caller.resourceScopes());
        assertTrue(caller.jobAllowed("job-1"));
        assertTrue(caller.jarAllowed("job-1"));
        assertNull(caller.tenant());
    }

    @Test
    void fullConstructorKeepsJobsAndJarsIndependent() {
        CallerIdentity caller = new CallerIdentity(
                "ops", "tenant-a", Set.of("orders"), Set.of("job-1"), Set.of("jar-7"), false, null);
        assertEquals("tenant-a", caller.tenant());
        assertTrue(caller.scopeAllowed("orders"));
        assertTrue(caller.jobAllowed("job-1"));
        assertFalse(caller.jobAllowed("jar-7"));
        assertTrue(caller.jarAllowed("jar-7"));
        assertFalse(caller.jarAllowed("job-1"));
    }

    @Test
    void derivedCopiesKeepEveryAllowList() {
        CallerIdentity caller = new CallerIdentity(
                "ops", "tenant-a", Set.of("orders"), Set.of("job-1"), Set.of("jar-7"), false, null);
        CallerIdentity readonly = caller.asReadonly().withOutboundAuth("Bearer downstream");
        assertTrue(readonly.readonly());
        assertEquals("tenant-a", readonly.tenant());
        assertEquals("Bearer downstream", readonly.outboundAuthHeader());
        assertTrue(readonly.jobAllowed("job-1"));
        assertTrue(readonly.jarAllowed("jar-7"));
    }
}
