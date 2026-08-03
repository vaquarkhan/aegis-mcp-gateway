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

import io.github.vaquarkhan.aegis.core.auth.NonceStore;

/**
 * The {@code ApprovalTokens} minter of LLD sections 5 and 11.
 *
 * <p>This is the LLD name for {@link Approval}, which remains the implementation and the type the
 * interceptor chain holds. The wrapper exists so code and documentation written against the low
 * level design compile against the same mint and verify surface.
 *
 * @author Viquar Khan
 */
public final class ApprovalTokens {

    private final Approval inner;

    public ApprovalTokens(String secret, NonceStore nonces) {
        this(new Approval(secret, nonces));
    }

    public ApprovalTokens(Approval approval) {
        this.inner = approval == null ? new Approval(null, new NonceStore()) : approval;
    }

    /** Mints a token bound to one tool, one scope and one expiry. */
    public String mint(String tool, String scope, long ttlMillis) {
        return inner.mint(tool, scope, ttlMillis);
    }

    /** Verifies signature, tool binding, scope binding, expiry and single use. */
    public boolean verify(String token, String tool, String scope) {
        return inner.verify(token, tool, scope);
    }

    public boolean configured() {
        return inner.configured();
    }

    /** The underlying implementation, for wiring into the interceptor chain. */
    public Approval delegate() {
        return inner;
    }
}
