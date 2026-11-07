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
package io.github.vaquarkhan.aegis.engine.spi;

import java.util.Map;

/**
 * Durable, append-only audit journal. {@link #append} returns only after the record is durable to
 * the sink's own standard; if it throws, the engine does not execute the call (fail-closed for
 * ALLOW_INTENT). A host can add its own sink (for example forwarding to Knox's audit service)
 * next to the bundled hash-chained file journal.
 */
public interface AuditSink extends AutoCloseable {

    /** @param record flat JSON-compatible map; the sink may add chain fields */
    void append(Map<String, Object> record) throws Exception;

    @Override
    default void close() {}
}
