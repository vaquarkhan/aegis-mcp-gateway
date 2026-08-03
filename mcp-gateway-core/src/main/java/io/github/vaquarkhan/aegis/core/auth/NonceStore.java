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

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-use nonce tracker backing approval token replay protection. Entries are evicted once the
 * token they belong to has expired, so the store stays bounded by the approval TTL.
 *
 * @author Viquar Khan
 */
public final class NonceStore {

    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    /** Returns true the first time a nonce is presented and false on every replay. */
    public boolean useOnce(String nonce, long expMillis) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() < now) {
                it.remove();
            }
        }
        return seen.putIfAbsent(nonce, expMillis) == null;
    }

    public int size() {
        return seen.size();
    }
}
