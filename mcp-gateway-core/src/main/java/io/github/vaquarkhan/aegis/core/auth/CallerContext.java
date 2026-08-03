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

import java.util.Optional;

/**
 * Thread-local caller identity set by the inbound auth filter and propagated onto backend worker
 * threads by the timeout executor.
 *
 * @author Viquar Khan
 */
public final class CallerContext {

    private static final ThreadLocal<CallerIdentity> CURRENT = new ThreadLocal<>();

    private CallerContext() {}

    public static void set(CallerIdentity identity) {
        CURRENT.set(identity);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<CallerIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
