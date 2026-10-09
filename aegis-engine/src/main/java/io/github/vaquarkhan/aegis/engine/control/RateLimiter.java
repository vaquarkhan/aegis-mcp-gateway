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
package io.github.vaquarkhan.aegis.engine.control;

import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.time.Duration;
import java.time.Instant;

/**
 * Step 6. Per-caller and per-caller-per-tool call rates, plus a weighted budget per caller where
 * write classes cost more. Every admitted call is charged: there is no response cache in 2.0, so
 * nothing can bypass the budget (0.1 cache hits did). Counters are atomic in the {@link StateStore}
 * and a refused call does not increment them.
 *
 * @param perCallerPerMinute        0 disables
 * @param perCallerPerToolPerMinute 0 disables
 * @param budgetPerHour             weighted units per caller per hour, 0 disables
 * @param readCost                  budget cost of a READ call
 * @param mutateCost                budget cost of a MUTATE call
 * @param destructiveCost           budget cost of a DESTRUCTIVE call
 */
public record RateLimiter(long perCallerPerMinute, long perCallerPerToolPerMinute, long budgetPerHour,
                          long readCost, long mutateCost, long destructiveCost) {

    public static final RateLimiter DISABLED = new RateLimiter(0, 0, 0, 1, 5, 20);

    /** @return null when admitted, else the deny code */
    public String admit(StateStore store, CallerIdentity caller, Action action, Instant now) {
        String pk = caller.principalKey();
        if (perCallerPerMinute > 0
                && !store.tryAcquire("rate:caller:" + pk, 1, perCallerPerMinute, Duration.ofMinutes(1), now)) {
            return DenyCodes.RATE_LIMITED;
        }
        if (perCallerPerToolPerMinute > 0
                && !store.tryAcquire("rate:tool:" + pk + ":" + action.tool(), 1, perCallerPerToolPerMinute,
                        Duration.ofMinutes(1), now)) {
            return DenyCodes.RATE_LIMITED;
        }
        if (budgetPerHour > 0) {
            long cost = action.toolClass() == ToolClass.DESTRUCTIVE ? destructiveCost
                    : action.toolClass() == ToolClass.MUTATE ? mutateCost : readCost;
            if (!store.tryAcquire("budget:" + pk, cost, budgetPerHour, Duration.ofHours(1), now)) {
                return DenyCodes.BUDGET_EXCEEDED;
            }
        }
        return null;
    }
}
