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
package io.github.vaquarkhan.aegis.core.auth;

import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPIFFE workload identity over mutual TLS.
 *
 * <p>Stub for 0.1.0. It reports whether a trust domain is configured and always denies peer
 * verification, so no unverified workload can be admitted.
 *
 * <p>TODO: obtain the X.509 SVID from the workload API socket, validate the peer chain against the
 * trust bundle, extract the SPIFFE ID from the URI subject alternative name, and map the trust
 * domain plus workload path onto {@link CallerIdentity} scopes. See section 10 of the design
 * document.
 *
 * @author Viquar Khan
 */
public final class SpiffeMtls {

    private static final Logger LOG = LoggerFactory.getLogger(SpiffeMtls.class);

    private static final String SPIFFE_SCHEME = "spiffe://";

    private final String trustDomain;
    private final String workloadApiSocket;

    public SpiffeMtls(String trustDomain, String workloadApiSocket) {
        this.trustDomain = trustDomain;
        this.workloadApiSocket = workloadApiSocket;
        LOG.warn("SPIFFE mTLS is a stub and denies all peers (trustDomain={}, workloadApi={})",
                trustDomain == null ? "-" : trustDomain,
                workloadApiSocket == null ? "-" : workloadApiSocket);
    }

    public boolean configured() {
        return trustDomain != null && !trustDomain.isBlank();
    }

    public String trustDomain() {
        return trustDomain;
    }

    public String workloadApiSocket() {
        return workloadApiSocket;
    }

    /** Shape check only. A well formed SPIFFE ID is necessary but never sufficient for admission. */
    public boolean looksLikeSpiffeId(String id) {
        if (id == null || !id.startsWith(SPIFFE_SCHEME) || id.length() <= SPIFFE_SCHEME.length()) {
            return false;
        }
        if (!configured()) {
            return false;
        }
        return id.regionMatches(SPIFFE_SCHEME.length(), trustDomain, 0, trustDomain.length());
    }

    /** Always empty in 0.1.0. */
    public Optional<CallerIdentity> verifyPeer(String spiffeId) {
        LOG.debug("SPIFFE peer verification denied: not implemented (id={})", spiffeId);
        return Optional.empty();
    }

    /**
     * Strict verification entry point.
     *
     * @throws UnsupportedOperationException always
     */
    public CallerIdentity requireVerifiedPeer(String spiffeId) {
        throw new UnsupportedOperationException(
                "SPIFFE mTLS verification is not implemented in 0.1.0; see DESIGN section 10");
    }

    /** Scopes a verified workload would receive once verification is implemented. */
    public Set<String> defaultScopes() {
        return Set.of();
    }
}
