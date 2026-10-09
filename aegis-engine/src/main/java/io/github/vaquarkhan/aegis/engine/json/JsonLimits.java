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
package io.github.vaquarkhan.aegis.engine.json;

/**
 * Hard bounds applied while parsing untrusted JSON. Every bound is checked during the parse, so an
 * oversized or deeply nested document is rejected before it is fully materialised.
 *
 * @param maxBytes          maximum encoded document size in bytes
 * @param maxDepth          maximum nesting depth of objects and arrays
 * @param maxStringChars    maximum length of any single string (keys included)
 * @param maxNumberChars    maximum length of the textual form of a number
 * @param maxObjectEntries  maximum members in one object
 * @param maxArrayElements  maximum elements in one array
 * @param maxTotalValues    maximum number of values in the whole document
 */
public record JsonLimits(
        int maxBytes,
        int maxDepth,
        int maxStringChars,
        int maxNumberChars,
        int maxObjectEntries,
        int maxArrayElements,
        int maxTotalValues) {

    /** Defaults sized for MCP JSON-RPC requests (1 MiB, depth 32). */
    public static final JsonLimits DEFAULT = new JsonLimits(1 << 20, 32, 256 * 1024, 64, 2_000, 10_000, 100_000);

    /** Looser defaults for operator-supplied configuration files (still bounded). */
    public static final JsonLimits CONFIG = new JsonLimits(8 << 20, 64, 1 << 20, 64, 20_000, 100_000, 2_000_000);

    /** Audit records may retain bounded response evidence, so permit one complete 8 MiB record. */
    public static final JsonLimits AUDIT = new JsonLimits(8 << 20, 64, 8 << 20, 64, 20_000, 100_000, 2_000_000);

    public JsonLimits {
        if (maxBytes <= 0 || maxDepth <= 0 || maxStringChars <= 0 || maxNumberChars <= 0
                || maxObjectEntries <= 0 || maxArrayElements <= 0 || maxTotalValues <= 0) {
            throw new IllegalArgumentException("all JSON limits must be positive");
        }
    }

    public JsonLimits withMaxBytes(int bytes) {
        return new JsonLimits(bytes, maxDepth, maxStringChars, maxNumberChars, maxObjectEntries,
                maxArrayElements, maxTotalValues);
    }

    public JsonLimits withMaxDepth(int depth) {
        return new JsonLimits(maxBytes, depth, maxStringChars, maxNumberChars, maxObjectEntries,
                maxArrayElements, maxTotalValues);
    }
}
