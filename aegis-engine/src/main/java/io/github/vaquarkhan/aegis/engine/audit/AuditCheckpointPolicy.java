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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Where and how often a {@link HashChainFileAuditSink} exports its head. Each checkpoint is one JSON
 * line {@code {"ts","journal","seq","head","reason"}} appended to {@code file}, which the operator
 * places on storage the gateway's journal writer cannot rewrite (another volume, a WORM or object-lock
 * mount, a log shipper's spool). {@link AuditCheckpointVerifier} later proves the journal still
 * contains every exported head, so truncating or regenerating the journal is detected.
 *
 * @param file          checkpoint file; must not be the journal itself
 * @param everyRecords  export after this many new records (at least 1)
 * @param every         also export on this interval when new records exist; {@link Duration#ZERO} disables the timer
 * @param clock         timestamp source for the {@code ts} field
 */
public record AuditCheckpointPolicy(Path file, long everyRecords, Duration every, Clock clock) {

    public AuditCheckpointPolicy {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(every, "every");
        Objects.requireNonNull(clock, "clock");
        if (everyRecords < 1) {
            throw new IllegalArgumentException("everyRecords must be at least 1");
        }
        if (every.isNegative()) {
            throw new IllegalArgumentException("every must not be negative");
        }
    }
}
