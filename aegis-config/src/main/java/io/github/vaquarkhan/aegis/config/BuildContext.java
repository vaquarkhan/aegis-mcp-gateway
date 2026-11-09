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
package io.github.vaquarkhan.aegis.config;

import io.github.vaquarkhan.aegis.engine.control.EgressBinding;
import io.github.vaquarkhan.aegis.engine.spi.AddressResolver;
import io.github.vaquarkhan.aegis.mcp.http.OutboundHttp;
import io.github.vaquarkhan.aegis.mcp.http.PinnedHttp;
import java.time.Duration;
import io.github.vaquarkhan.aegis.engine.spi.AuditSink;
import io.github.vaquarkhan.aegis.engine.spi.CredentialProvider;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.Function;

/**
 * What the host contributes when building a gateway. Everything has a default; a host overrides
 * only what it owns. For example the Knox extension can contribute a Knox-backed audit sink or a
 * token-exchange credential provider without the configuration format changing.
 *
 * @param env               secret lookup (defaults to process environment)
 * @param baseDir           directory relative paths in the config are resolved against
 * @param clock             time source
 * @param resolver          DNS for the egress control
 * @param extraHooks        host-supplied extension controls, run after configured hooks
 * @param extraAuditSink    additional sink written in addition to the configured journal (may be null)
 * @param credentialOverride replaces the configured static credentials when non-null
 * @param stateStore        shared state store for multi-replica deployments (null = in-memory)
 * @param egressBinding     late-bound gateway egress control used by {@link #outbound}; the factory
 *                          binds a fresh one per build (null = a fresh unbound binding)
 */
public record BuildContext(
        Function<String, String> env,
        Path baseDir,
        Clock clock,
        AddressResolver resolver,
        List<GovernanceHook> extraHooks,
        AuditSink extraAuditSink,
        CredentialProvider credentialOverride,
        StateStore stateStore,
        EgressBinding egressBinding) {

    public BuildContext {
        env = env == null ? System::getenv : env;
        baseDir = baseDir == null ? Path.of(".") : baseDir;
        clock = clock == null ? Clock.systemUTC() : clock;
        resolver = resolver == null ? AddressResolver.SYSTEM : resolver;
        extraHooks = extraHooks == null ? List.of() : List.copyOf(extraHooks);
        egressBinding = egressBinding == null ? new EgressBinding() : egressBinding;
    }

    /** Source-compatible constructor (pre egress pinning). */
    public BuildContext(Function<String, String> env, Path baseDir, Clock clock, AddressResolver resolver,
                        List<GovernanceHook> extraHooks, AuditSink extraAuditSink, CredentialProvider credentialOverride,
                        StateStore stateStore) {
        this(env, baseDir, clock, resolver, extraHooks, extraAuditSink, credentialOverride, stateStore, null);
    }

    public static BuildContext defaults(Path baseDir) {
        return new BuildContext(null, baseDir, null, null, null, null, null, null, null);
    }

    public BuildContext withEnv(Function<String, String> newEnv) {
        return new BuildContext(newEnv, baseDir, clock, resolver, extraHooks, extraAuditSink, credentialOverride, stateStore,
                egressBinding);
    }

    /** Same context with a new, unbound egress binding (one per gateway build). */
    public BuildContext withFreshEgressBinding() {
        return new BuildContext(env, baseDir, clock, resolver, extraHooks, extraAuditSink, credentialOverride, stateStore,
                new EgressBinding());
    }

    /**
     * Pinned outbound transport for providers: every exchange is resolved and checked by the
     * gateway egress control once, and the socket connects only to those checked addresses.
     * Connections are refused until the gateway's egress control is bound.
     */
    public OutboundHttp outbound(Duration connectTimeout) {
        return new PinnedHttp(egressBinding::pin, connectTimeout, null,
                egressBinding.proxy() instanceof PinnedHttp.Proxy p ? p : null);
    }
}
