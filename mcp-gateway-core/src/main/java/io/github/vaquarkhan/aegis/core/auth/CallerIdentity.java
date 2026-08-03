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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Resolved caller identity: subject, tenant, scopes, job and jar allow lists, and the optional
 * outbound credential.
 *
 * <p>This is the {@code CALLER_IDENTITY} model of LLD section 5 and section 15. Engine specific
 * notions such as Kafka topics, Iceberg namespaces or tables are expressed as opaque resource
 * scope strings, so the gateway needs no per-engine scope model. Flink job ids and jar ids get
 * their own allow lists because they are the two resources a destructive tool binds to most often.
 * {@code *} is the wildcard everywhere.
 *
 * <p>An empty allow list denies everything, so a misconfigured caller fails closed rather than
 * inheriting full access.
 *
 * @author Viquar Khan
 */
public final class CallerIdentity {

    /** Identity used for stdio, where transport level authentication does not apply. */
    public static final String STDIO_CALLER = "stdio";

    private final String subject;
    private final String tenant;
    private final Set<String> scopes;
    private final Set<String> jobsAllow;
    private final Set<String> jarsAllow;
    private final boolean readonly;
    private final String outboundAuthHeader;

    /**
     * Full LLD constructor.
     *
     * @param subject stable caller identifier, also reported as {@code callerId}
     * @param tenant owning tenant, or {@code null} when the deployment is single tenant
     * @param scopes resource scopes such as topics, namespaces and tables
     * @param jobsAllow job ids this caller may target, {@code null} to mirror {@code scopes}
     * @param jarsAllow jar ids this caller may target, {@code null} to mirror {@code scopes}
     * @param readonly true when the caller may only invoke READ class tools
     * @param outboundAuthHeader optional outbound {@code Authorization} header value
     */
    public CallerIdentity(
            String subject,
            String tenant,
            Set<String> scopes,
            Set<String> jobsAllow,
            Set<String> jarsAllow,
            boolean readonly,
            String outboundAuthHeader) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.tenant = blankToNull(tenant);
        this.scopes = frozen(scopes);
        this.jobsAllow = jobsAllow == null ? this.scopes : frozen(jobsAllow);
        this.jarsAllow = jarsAllow == null ? this.scopes : frozen(jarsAllow);
        this.readonly = readonly;
        this.outboundAuthHeader = blankToNull(outboundAuthHeader);
    }

    /** Single tenant caller whose scopes also govern jobs and jars. */
    public CallerIdentity(String callerId, Set<String> resourceScopes, boolean readonly) {
        this(callerId, null, resourceScopes, null, null, readonly, null);
    }

    /** Single tenant caller with an outbound credential. */
    public CallerIdentity(
            String callerId, Set<String> resourceScopes, boolean readonly, String outboundAuthHeader) {
        this(callerId, null, resourceScopes, null, null, readonly, outboundAuthHeader);
    }

    /** Full-scope read-only identity, the safe default when nothing else is configured. */
    public static CallerIdentity readonly(String callerId) {
        return new CallerIdentity(callerId, Set.of("*"), true);
    }

    /** Caller carrying separate job and jar allow lists, as loaded from the token registry. */
    public static CallerIdentity of(
            String subject,
            Set<String> jobsAllow,
            Set<String> jarsAllow,
            boolean readonly,
            String outboundAuthHeader) {
        Set<String> union = new LinkedHashSet<>();
        if (jobsAllow != null) {
            union.addAll(jobsAllow);
        }
        if (jarsAllow != null) {
            union.addAll(jarsAllow);
        }
        return new CallerIdentity(subject, null, union, jobsAllow, jarsAllow, readonly, outboundAuthHeader);
    }

    public CallerIdentity withOutboundAuth(String header) {
        return new CallerIdentity(subject, tenant, scopes, jobsAllow, jarsAllow, readonly, header);
    }

    public CallerIdentity withTenant(String value) {
        return new CallerIdentity(subject, value, scopes, jobsAllow, jarsAllow, readonly, outboundAuthHeader);
    }

    public CallerIdentity asReadonly() {
        return readonly
                ? this
                : new CallerIdentity(subject, tenant, scopes, jobsAllow, jarsAllow, true, outboundAuthHeader);
    }

    /** LLD name for the caller identifier. */
    public String subject() {
        return subject;
    }

    public String callerId() {
        return subject;
    }

    /** Owning tenant, or {@code null} in a single tenant deployment. */
    public String tenant() {
        return tenant;
    }

    /** LLD name for the resource scope set. */
    public Set<String> scopes() {
        return scopes;
    }

    public Set<String> resourceScopes() {
        return scopes;
    }

    public Set<String> jobsAllow() {
        return jobsAllow;
    }

    public Set<String> jarsAllow() {
        return jarsAllow;
    }

    public boolean readonly() {
        return readonly;
    }

    /** Optional outbound {@code Authorization} header value, or {@code null}. */
    public String outboundAuthHeader() {
        return outboundAuthHeader;
    }

    /**
     * Scope check. An empty scope set denies everything, so a misconfigured caller fails closed
     * rather than inheriting full access.
     */
    public boolean scopeAllowed(String resource) {
        return allowed(scopes, resource);
    }

    /** True when the caller may target this Flink job id. */
    public boolean jobAllowed(String jobId) {
        return allowed(jobsAllow, jobId);
    }

    /** True when the caller may target this jar id. */
    public boolean jarAllowed(String jarId) {
        return allowed(jarsAllow, jarId);
    }

    @Override
    public String toString() {
        return "CallerIdentity[" + subject
                + (tenant == null ? "" : "@" + tenant)
                + ", scopes=" + scopes
                + ", jobs=" + jobsAllow
                + ", jars=" + jarsAllow
                + ", readonly=" + readonly + "]";
    }

    private static boolean allowed(Set<String> allowList, String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (allowList.isEmpty()) {
            return false;
        }
        return allowList.contains("*") || allowList.contains(value);
    }

    private static Set<String> frozen(Set<String> in) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(in == null ? Set.of() : in));
    }

    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return (t.isEmpty() || "-".equals(t)) ? null : t;
    }
}
