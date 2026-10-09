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

import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import java.util.Map;

/**
 * Policy decision point. The engine is the enforcement point; the PDP only answers allow or deny.
 * Any exception is treated as deny (fail-closed). Implementations receive the full canonical
 * action, including resources and the argument digest, never only caller + tool name (0.1's OPA
 * integration ignored arguments and cached per caller+tool).
 */
public interface PolicyDecisionPoint {

    Result decide(CallerIdentity caller, Action action, Map<String, Object> backendArguments) throws Exception;

    /** @param allow decision; @param reason operator-facing reason (no argument values) */
    record Result(boolean allow, String reason) {
        public static Result allow(String reason) {
            return new Result(true, reason);
        }

        public static Result deny(String reason) {
            return new Result(false, reason);
        }
    }
}
