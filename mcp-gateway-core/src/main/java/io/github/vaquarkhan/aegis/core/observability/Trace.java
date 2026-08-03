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
package io.github.vaquarkhan.aegis.core.observability;

import io.github.vaquarkhan.aegis.core.interceptor.Decision;
import io.github.vaquarkhan.aegis.core.interceptor.Observer;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Per-call correlation id. Stored in a thread local and mirrored into the SLF4J mapped diagnostic
 * context under key {@code trace} so every stderr log line can be joined to one tool call.
 *
 * @author Viquar Khan
 */
public final class Trace implements Observer {

    public static final String MDC_KEY = "trace";

    private static final Logger LOG = LoggerFactory.getLogger(Trace.class);
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final SecureRandom RNG = new SecureRandom();

    public static String newId() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        String id = sb.toString();
        set(id);
        return id;
    }

    public static void set(String id) {
        CURRENT.set(id);
        if (id == null || id.isBlank()) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, id);
        }
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    @Override
    public void onOutcome(CallContext ctx, Decision decision, long elapsedMillis) {
        switch (decision.severity()) {
            case ERROR -> LOG.error("tool={} caller={} outcome={} ms={}",
                    ctx.toolName(), callerOf(ctx), decision.auditOutcome(), elapsedMillis);
            case WARN -> LOG.warn("tool={} caller={} outcome={} ms={}",
                    ctx.toolName(), callerOf(ctx), decision.auditOutcome(), elapsedMillis);
            default -> LOG.info("tool={} caller={} outcome={} ms={}",
                    ctx.toolName(), callerOf(ctx), decision.auditOutcome(), elapsedMillis);
        }
    }

    private static String callerOf(CallContext ctx) {
        return ctx.caller() == null ? "-" : ctx.caller().callerId();
    }
}
