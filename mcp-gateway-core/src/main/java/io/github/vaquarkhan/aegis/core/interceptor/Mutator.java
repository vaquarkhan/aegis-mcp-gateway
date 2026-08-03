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
package io.github.vaquarkhan.aegis.core.interceptor;

import io.github.vaquarkhan.aegis.core.spi.CallContext;

/**
 * Interceptor that may rewrite the call context, for example to sanitize arguments or to attach a
 * resolved outbound credential. Mutators run first and atomically, before any validator, and they
 * cannot deny; a mutator that wants to deny must also be registered as a {@link Validator}.
 *
 * @author Viquar Khan
 */
public interface Mutator extends Interceptor {

    CallContext mutate(CallContext ctx);

    @Override
    default Decision apply(CallContext ctx) {
        return Decision.allow();
    }

    @Override
    default Phase phase() {
        return Phase.MUTATION;
    }

    static Mutator of(String name, int step, java.util.function.UnaryOperator<CallContext> fn) {
        return new Mutator() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int step() {
                return step;
            }

            @Override
            public CallContext mutate(CallContext ctx) {
                return fn.apply(ctx);
            }
        };
    }
}
