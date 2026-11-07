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

/**
 * An optional {@link StateStore} capability for signed-document rollback protection. It is separate
 * from {@code StateStore} so existing state providers stay source and binary compatible: a provider
 * that has not implemented this atomic operation cannot accidentally claim to protect numbered files.
 */
public interface HighWaterStateStore extends StateStore {

    /**
     * Atomically records {@code candidate} as this key's numeric high-water mark when it is higher
     * than the stored value, then returns the resulting mark. A candidate of zero reads the mark
     * without creating it. Values are positive whole numbers; an unreadable stored value fails
     * closed with {@link IllegalStateException}.
     */
    long updateHighWaterMark(String key, long candidate);
}
