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

import io.github.vaquarkhan.aegis.engine.spi.AddressResolver;
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
 */
public record BuildContext(
        Function<String, String> env,
        Path baseDir,
        Clock clock,
        AddressResolver resolver,
        List<GovernanceHook> extraHooks,
        AuditSink extraAuditSink,
        CredentialProvider credentialOverride,
        StateStore stateStore) {

    public BuildContext {
        env = env == null ? System::getenv : env;
        baseDir = baseDir == null ? Path.of(".") : baseDir;
        clock = clock == null ? Clock.systemUTC() : clock;
        resolver = resolver == null ? AddressResolver.SYSTEM : resolver;
        extraHooks = extraHooks == null ? List.of() : List.copyOf(extraHooks);
    }

    public static BuildContext defaults(Path baseDir) {
        return new BuildContext(null, baseDir, null, null, null, null, null, null);
    }

    public BuildContext withEnv(Function<String, String> newEnv) {
        return new BuildContext(newEnv, baseDir, clock, resolver, extraHooks, extraAuditSink, credentialOverride, stateStore);
    }
}
