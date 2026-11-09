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
/*
 * O-6 A: gateway-side subject revocation on the Knox path. Knox supplies no token id or issue time, so
 * a subject entry refuses the subject, the primary actor and any delegated actor until it expires.
 */
package io.github.vaquarkhan.aegis.knox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.control.TokenRevocations;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnoxRevocationTest {

    @Test
    void revokedSubjectActorOrDelegateIsRefusedOnTheKnoxPath() {
        TokenRevocations revs = new TokenRevocations(new InMemoryStateStore(), Clock.systemUTC());
        CallerIdentity alice = new CallerIdentity("alice", "alice", Set.of("analysts"), "knox:sandbox", "knox", List.of());
        CallerIdentity delegated = new CallerIdentity("agent-7", "bob", Set.of("analysts"), "knox:sandbox", "knox",
                List.of("agent-7", "agent-root"));

        assertSame(alice, KnoxIdentityBridge.unlessRevoked(alice, revs), "nothing revoked");
        assertSame(alice, KnoxIdentityBridge.unlessRevoked(alice, null), "no list bound: pass through");
        assertNull(KnoxIdentityBridge.unlessRevoked(null, revs), "no Knox subject stays null");
        assertTrue(revs.acceptsIssuer("knox:sandbox"), "the Knox issuer is registered so admin revocations under it are accepted");

        revs.revokeSubject("knox:sandbox", "alice", Duration.ofHours(1));
        assertNull(KnoxIdentityBridge.unlessRevoked(alice, revs), "effective subject revoked");
        assertSame(delegated, KnoxIdentityBridge.unlessRevoked(delegated, revs), "other callers unaffected");

        revs.revokeSubject("knox:sandbox", "agent-root", Duration.ofHours(1));
        assertNull(KnoxIdentityBridge.unlessRevoked(delegated, revs), "a revoked actor deep in the chain refuses the request");

        CallerIdentity primaryOnly = new CallerIdentity("svc", "carol", Set.of(), "knox:sandbox", "knox", List.of());
        revs.revokeSubject("knox:sandbox", "svc", Duration.ofHours(1));
        assertNull(KnoxIdentityBridge.unlessRevoked(primaryOnly, revs), "the primary actor is checked too");

        CallerIdentity otherTopology = new CallerIdentity("alice", "alice", Set.of(), "knox:prod", "knox", List.of());
        assertEquals(otherTopology, KnoxIdentityBridge.unlessRevoked(otherTopology, revs), "issuer-scoped: another topology is unaffected");
        System.out.println("THREATMODEL|SCAN-O6|AI scanner readiness O-6|KnoxIdentityBridge.unlessRevoked|FIXED|"
                + "subject, primary actor and delegation chain checked against the shared revocation list on the Knox path");
    }
}
