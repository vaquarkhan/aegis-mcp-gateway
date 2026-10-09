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
package io.github.vaquarkhan.aegis.engine.audit;

import io.github.vaquarkhan.aegis.engine.spi.AuditSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test and embedding sink. Can be told to fail, to exercise the fail-closed path. It is not durable
 * and not hash-chained, so it cannot be selected from configuration: every configured gateway
 * journals to {@link HashChainFileAuditSink}. Only code that builds an engine by hand can use it.
 */
public final class InMemoryAuditSink implements AuditSink {

    private final List<Map<String, Object>> records = new ArrayList<>();
    private volatile boolean failing;

    @Override
    public synchronized void append(Map<String, Object> record) throws Exception {
        if (failing) {
            throw new java.io.IOException("audit sink unavailable (simulated)");
        }
        records.add(Map.copyOf(record));
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public synchronized List<Map<String, Object>> records() {
        return List.copyOf(records);
    }
}
