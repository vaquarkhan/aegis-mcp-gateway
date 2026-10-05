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
package io.github.vaquarkhan.aegis.gateway;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.audit.AuditCheckpointVerifier;
import io.github.vaquarkhan.aegis.engine.audit.AuditChainVerifier;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * {@code java -jar aegis-gateway-all.jar --config aegis.yaml [--stdio]}
 * <br>{@code java -jar aegis-gateway-all.jar --verify-audit audit.jsonl [--checkpoints checkpoints.jsonl]}
 * <br>{@code java -jar aegis-gateway-all.jar --hash-token} (reads a token on stdin, prints its SHA-256)
 * <br>{@code java -jar aegis-gateway-all.jar --manifest-keygen|--manifest-create|--manifest-sign|--manifest-verify ...}
 * (signed catalog manifests, see {@link CatalogManifestCommands})
 * <br>{@code java -jar aegis-gateway-all.jar --openapi-draft openapi.yaml draft.yaml [--source-id id] [--base-url url]}
 * (a reviewable draft configuration, see {@link OpenApiDraftCommand})
 */
public final class Main {

    /** Exit codes: 0 ok, 1 audit chain broken, 2 usage or configuration error. */
    static final int OK = 0;
    static final int BROKEN = 1;
    static final int USAGE = 2;

    private Main() {}

    public static void main(String[] args) throws Exception {
        int code = run(args, System.in, System.out, System.err, System::getenv, true);
        if (code != OK) {
            System.exit(code);
        }
    }

    /**
     * Everything {@link #main} does, with the process streams and environment passed in (testable).
     *
     * @param serve when false the HTTP server mode returns after start-up validation instead of blocking
     */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err, Function<String, String> env,
                   boolean serve) throws Exception {
        String config = null;
        boolean stdio = false;
        String verifyAudit = null;
        String checkpoints = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        err.println("--config needs a file");
                        return USAGE;
                    }
                    config = args[++i];
                }
                case "--stdio" -> stdio = true;
                case "--verify-audit" -> {
                    if (i + 1 >= args.length) {
                        err.println("--verify-audit needs a file");
                        return USAGE;
                    }
                    verifyAudit = args[++i];
                }
                case "--checkpoints" -> {
                    if (i + 1 >= args.length) {
                        err.println("--checkpoints needs a file");
                        return USAGE;
                    }
                    checkpoints = args[++i];
                }
                case "--manifest-keygen", "--manifest-create", "--manifest-sign", "--manifest-verify" -> {
                    String[] rest = java.util.Arrays.copyOfRange(args, i + 1, args.length);
                    return CatalogManifestCommands.run(args[i], rest, out, err);
                }
                case "--openapi-draft" -> {
                    return OpenApiDraftCommand.run(java.util.Arrays.copyOfRange(args, i + 1, args.length), out, err);
                }
                case "--hash-token" -> {
                    String t = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (t.isEmpty()) {
                        err.println("no token on stdin");
                        return USAGE;
                    }
                    out.println(Digests.sha256Hex(t));
                    return OK;
                }
                default -> {
                    err.println("unknown argument: " + args[i]);
                    return USAGE;
                }
            }
        }
        if (checkpoints != null && verifyAudit == null) {
            err.println("--checkpoints is used with --verify-audit <file>");
            return USAGE;
        }
        if (verifyAudit != null) {
            if (config != null || stdio) {
                err.println("--verify-audit runs on its own, without --config or --stdio");
                return USAGE;
            }
            if (checkpoints != null) {
                AuditCheckpointVerifier.Report r = AuditCheckpointVerifier.verify(Path.of(verifyAudit), Path.of(checkpoints));
                out.println(AuditCheckpointVerifier.describe(r));
                return r.ok() ? OK : BROKEN;
            }
            AuditChainVerifier.Report r = AuditChainVerifier.verify(Path.of(verifyAudit));
            out.println((r.ok() ? "OK" : "BROKEN") + " records=" + r.records() + " head=" + r.head()
                    + (r.ok() ? "" : " error=" + r.error()));
            return r.ok() ? OK : BROKEN;
        }
        if (config == null) {
            err.println("usage: --config <aegis.yaml|aegis.json> [--stdio] | --verify-audit <file> [--checkpoints <file>]"
                    + " | --hash-token | --manifest-keygen|--manifest-create|--manifest-sign|--manifest-verify ..."
                    + " | --openapi-draft <openapi.yaml> <draft.yaml> [--source-id <id>] [--base-url <url>]");
            return USAGE;
        }
        AegisServer.applyServerTimeLimits(); // before any JDK HTTP server class initialises
        Path cfg = Path.of(config).toAbsolutePath();
        Gateway gateway = GatewayFactory.build(cfg, BuildContext.defaults(cfg.getParent()).withEnv(env));
        OwnServerSecuritySettings settings;
        try {
            settings = OwnServerSecuritySettings.parse(gateway.hostSection("host"), cfg.getParent(), gateway.secrets(),
                    Clock.systemUTC());
        } catch (RuntimeException e) {
            gateway.close();
            throw e;
        }
        if (stdio) {
            if (settings.stdioIdentity() == null) {
                err.println("--stdio requires host.stdio.actor in the configuration");
                gateway.close();
                return USAGE;
            }
            try (gateway) {
                StdioHost.run(gateway, settings.stdioIdentity(), in, out);
            }
            return OK;
        }
        AegisServer server = new AegisServer(gateway, settings);
        server.start();
        if (!serve) {
            server.close();
            return OK;
        }
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stop.countDown();
        }, "aegis-shutdown"));
        stop.await();
        return OK;
    }
}
